package com.kalo.ride.service;

import com.kalo.assignment.entity.DriverVehicleAssignment;
import com.kalo.assignment.repository.DriverVehicleAssignmentRepository;
import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.common.util.DistanceCalculator;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.location.entity.DriverLocation;
import com.kalo.location.repository.DriverLocationRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.service.CompanyAvailabilityChecker;
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
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import com.kalo.vehicle.entity.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RideSearchServiceImpl
        implements RideSearchService {

    private static final Duration RIDE_REQUEST_DURATION =
            Duration.ofMinutes(5);

    private static final Duration MAX_LOCATION_AGE =
            Duration.ofMinutes(2);

    private static final double MAX_SEARCH_RADIUS_KM =
            10.0;

    private final RideRequestRepository rideRequestRepository;
    private final DriverLocationRepository driverLocationRepository;
    private final DriverVehicleAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final RideOfferRepository rideOfferRepository;
    private final RideRepository rideRepository;
    private final CompanyAvailabilityChecker companyAvailabilityChecker;

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
        List<RideStatus> activeStatuses =
                List.of(
                        RideStatus.REQUESTED,
                        RideStatus.ACCEPTED,
                        RideStatus.DRIVER_ASSIGNED,
                        RideStatus.DRIVER_ARRIVING,
                        RideStatus.DRIVER_ARRIVED,
                        RideStatus.IN_PROGRESS
                );

        if (rideRepository
                .existsByCustomerIdAndStatusIn(
                        customer.getId(),
                        activeStatuses
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
         * LOAD AVAILABLE DRIVER LOCATIONS
         * =====================================================
         */

        Instant minimumLocationTime =
                now.minus(
                        MAX_LOCATION_AGE
                );

        List<DriverLocation> locations =
                driverLocationRepository
                        .findAvailableDriverLocations(
                                DriverAvailabilityStatus.ONLINE,
                                DriverStatus.ACTIVE,
                                VerificationStatus.APPROVED,
                                CompanyStatus.ACTIVE,
                                minimumLocationTime
                        );

        /*
         * =====================================================
         * STEP 3
         * BUILD AVAILABLE TAXI CANDIDATES
         * =====================================================
         */

        List<TaxiOptionCandidate> candidates =
                new ArrayList<>();

        for (DriverLocation location : locations) {

            Driver driver =
                    location.getDriver();

            /*
             * Calculate straight-line distance
             * between pickup and driver location.
             */
            double distanceKm =
                    DistanceCalculator
                            .calculateDistanceKm(
                                    request.pickupLatitude(),
                                    request.pickupLongitude(),
                                    location.getLatitude(),
                                    location.getLongitude()
                            );

            /*
             * Ignore drivers outside search radius.
             */
            if (distanceKm > MAX_SEARCH_RADIUS_KM) {
                continue;
            }

            /*
             * Driver must have an active vehicle assignment.
             */
            DriverVehicleAssignment assignment =
                    assignmentRepository
                            .findActiveAssignmentWithVehicle(
                                    driver.getId()
                            )
                            .orElse(null);

            if (assignment == null) {
                continue;
            }

            Vehicle vehicle =
                    assignment.getVehicle();

            TaxiCompany company =
                    driver.getCompany();

            /*
             * Company must currently accept bookings.
             */
            if (!company.isBookingEnabled()) {
                continue;
            }

            /*
             * Company must expose at least one
             * supported payment method.
             */
            if (company.getPaymentMethods() == null
                    || company.getPaymentMethods().isEmpty()) {

                continue;
            }

            /*
             * Company must currently be operational
             * according to its configured operating hours
             * and service-area rules.
             */
            if (!companyAvailabilityChecker.isAvailable(
                    company,
                    request.pickupLatitude(),
                    request.pickupLongitude(),
                    now
            )) {

                continue;
            }

            candidates.add(
                    new TaxiOptionCandidate(
                            company,
                            driver,
                            vehicle,
                            distanceKm
                    )
            );
        }

        /*
         * =====================================================
         * STEP 4
         * SORT BY NEAREST DRIVER
         * =====================================================
         */

        candidates.sort(
                Comparator.comparingDouble(
                        TaxiOptionCandidate::distanceKm
                )
        );

        /*
         * =====================================================
         * STEP 5
         * KEEP ONLY ONE RESULT PER COMPANY
         * =====================================================
         *
         * Example:
         *
         * ABC Taxi
         * Driver 1 -> 0.7 km
         * Driver 2 -> 1.5 km
         *
         * Customer sees ABC Taxi only once,
         * represented by Driver 1.
         */

        Map<Long, TaxiOptionCandidate> nearestByCompany =
                new LinkedHashMap<>();

        for (TaxiOptionCandidate candidate : candidates) {

            nearestByCompany.putIfAbsent(
                    candidate.company()
                            .getId(),
                    candidate
            );
        }

        /*
         * =====================================================
         * STEP 6
         * CREATE RIDE OFFERS
         * =====================================================
         */

        List<TaxiOptionResponse> taxiOptions =
                new ArrayList<>();

        for (TaxiOptionCandidate candidate
                : nearestByCompany.values()) {

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
         * STEP 7
         * RETURN SEARCH RESULT
         * =====================================================
         */

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

    /*
     * =========================================================
     * INTERNAL SEARCH CANDIDATE
     * =========================================================
     *
     * This record stays inside this class.
     * Do NOT create a separate Java file.
     */

    private record TaxiOptionCandidate(

            TaxiCompany company,

            Driver driver,

            Vehicle vehicle,

            double distanceKm

    ) {
    }
}