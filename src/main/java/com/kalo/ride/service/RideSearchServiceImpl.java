package com.kalo.ride.service;

import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.ride.dto.CreateRideRequest;
import com.kalo.ride.dto.RideSearchResponse;
import com.kalo.ride.dto.TaxiOptionResponse;
import com.kalo.ride.entity.RideOffer;
import com.kalo.ride.entity.RideRequest;
import com.kalo.ride.enums.RideRequestStatus;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideOfferRepository;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.repository.RideRequestRepository;
import com.kalo.ride.service.TaxiAvailabilityFinder.AvailableTaxi;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideSearchServiceImpl
        implements RideSearchService {

    private static final Duration RIDE_REQUEST_DURATION =
            Duration.ofMinutes(5);

    private final RideRequestRepository rideRequestRepository;
    private final TaxiAvailabilityFinder taxiAvailabilityFinder;
    private final UserRepository userRepository;
    private final RideOfferRepository rideOfferRepository;
    private final RideRepository rideRepository;

    @Override
    @Transactional
    public RideSearchResponse searchTaxis(
            CreateRideRequest request
    ) {

        User customer =
                getCurrentCustomer();

        /*
         * Customer cannot create another search
         * while an active ride already exists.
         */
        if (rideRepository
                .existsByCustomerIdAndStatusIn(
                        customer.getId(),
                        RideStatus.ACTIVE_STATUSES
                )) {

            throw new ConflictException(
                    "Customer already has an active ride"
            );
        }

        validateCoordinates(
                request
        );

        Instant now =
                Instant.now();

        /*
         * =====================================================
         * STEP 1
         * CREATE RIDE REQUEST
         * =====================================================
         */

        RideRequest rideRequest =
                new RideRequest();

        rideRequest.setCustomer(
                customer
        );

        rideRequest.setPickupLatitude(
                request.pickupLatitude()
        );

        rideRequest.setPickupLongitude(
                request.pickupLongitude()
        );

        rideRequest.setPickupAddress(
                normalize(
                        request.pickupAddress()
                )
        );

        rideRequest.setDestinationLatitude(
                request.destinationLatitude()
        );

        rideRequest.setDestinationLongitude(
                request.destinationLongitude()
        );

        rideRequest.setDestinationAddress(
                normalize(
                        request.destinationAddress()
                )
        );

        rideRequest.setStatus(
                RideRequestStatus.SEARCHING
        );

        rideRequest.setExpiresAt(
                now.plus(
                        RIDE_REQUEST_DURATION
                )
        );

        RideRequest savedRideRequest =
                rideRequestRepository.save(
                        rideRequest
                );

        /*
         * =====================================================
         * STEP 2
         * FIND AVAILABLE COMPANIES
         * =====================================================
         *
         * Shared with the public availability endpoint, so a guest and a
         * signed-in customer are judged by identical rules.
         */

        List<AvailableTaxi> candidates =
                taxiAvailabilityFinder.findNearestPerCompany(
                        request.pickupLatitude(),
                        request.pickupLongitude(),
                        now
                );

        /*
         * =====================================================
         * STEP 3
         * CREATE RIDE OFFERS
         * =====================================================
         */

        List<TaxiOptionResponse> taxiOptions =
                new ArrayList<>();

        for (AvailableTaxi candidate : candidates) {

            RideOffer offer =
                    new RideOffer();

            offer.setRideRequest(
                    savedRideRequest
            );

            offer.setCompany(
                    candidate.company()
            );

            offer.setNearestDriver(
                    candidate.driver()
            );

            offer.setVehicle(
                    candidate.vehicle()
            );

            offer.setDistanceKm(
                    roundDistance(
                            candidate.distanceKm()
                    )
            );

            RideOffer savedOffer =
                    rideOfferRepository.save(
                            offer
                    );

            TaxiOptionResponse response =
                    new TaxiOptionResponse(
                            savedOffer.getId(),

                            candidate.company()
                                    .getId(),

                            candidate.company()
                                    .getDisplayName(),

                            candidate.company()
                                    .getRating(),

                            candidate.company()
                                    .getRatingCount(),

                            savedOffer.getDistanceKm(),

                            candidate.driver()
                                    .getId(),

                            candidate.driver()
                                    .getRating(),

                            candidate.driver()
                                    .getRatingCount(),

                            candidate.vehicle()
                                    .getId(),

                            candidate.vehicle()
                                    .getPlateNumber(),

                            candidate.vehicle()
                                    .getBrand(),

                            candidate.vehicle()
                                    .getModel(),

                            candidate.vehicle()
                                    .getVehicleType(),

                            Set.copyOf(
                                    candidate.company()
                                            .getPaymentMethods()
                            ),

                            "Final price by taximeter"
                    );

            taxiOptions.add(
                    response
            );
        }

        /*
         * =====================================================
         * STEP 4
         * RETURN SEARCH RESULT
         * =====================================================
         */

        log.info(
                "Taxi search completed: rideRequestId={} customerId={} candidates={} offers={}",
                savedRideRequest.getId(),
                customer.getId(),
                candidates.size(),
                taxiOptions.size()
        );

        return new RideSearchResponse(
                savedRideRequest.getId(),
                savedRideRequest.getStatus(),
                savedRideRequest.getExpiresAt(),
                taxiOptions
        );
    }

    /*
     * =========================================================
     * CURRENT CUSTOMER
     * =========================================================
     */

    private User getCurrentCustomer() {

        String phone =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        User user =
                userRepository
                        .findByPhone(
                                phone
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "User not found"
                                )
                        );

        if (user.getRole()
                != UserRole.CUSTOMER) {

            throw new InvalidOperationException(
                    "Only customers can search for taxis"
            );
        }

        if (user.getStatus()
                != UserStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Customer account is not active"
            );
        }

        return user;
    }

    /*
     * =========================================================
     * COORDINATE VALIDATION
     * =========================================================
     */

    private void validateCoordinates(
            CreateRideRequest request
    ) {

        boolean sameLocation =
                Double.compare(
                        request.pickupLatitude(),
                        request.destinationLatitude()
                ) == 0
                        &&
                        Double.compare(
                                request.pickupLongitude(),
                                request.destinationLongitude()
                        ) == 0;

        if (sameLocation) {

            throw new InvalidOperationException(
                    "Pickup and destination cannot be the same"
            );
        }
    }

    /*
     * =========================================================
     * STRING NORMALIZATION
     * =========================================================
     */

    private String normalize(
            String value
    ) {

        if (value == null
                || value.isBlank()) {

            return null;
        }

        return value.trim();
    }

    /*
     * =========================================================
     * DISTANCE ROUNDING
     * =========================================================
     */

    private double roundDistance(
            double distance
    ) {

        return Math.round(
                distance * 100.0
        ) / 100.0;
    }

}