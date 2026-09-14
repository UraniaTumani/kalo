package com.kalo.rating.service;

import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.rating.dto.CreateRideRatingRequest;
import com.kalo.rating.dto.RideRatingResponse;
import com.kalo.rating.entity.RideRating;
import com.kalo.rating.repository.RideRatingRepository;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RideRatingServiceImpl
        implements RideRatingService {

    private final RideRatingRepository rideRatingRepository;
    private final RideRepository rideRepository;
    private final UserRepository userRepository;
    private final DriverRepository driverRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final com.kalo.notification.service.CompanyNotificationService notificationService;

    @Override
    @Transactional
    public RideRatingResponse rateRide(
            Long rideId,
            CreateRideRatingRequest request
    ) {

        User customer =
                getCurrentCustomer();

        Ride ride =
                rideRepository
                        .findByIdAndCustomerId(
                                rideId,
                                customer.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Ride not found"
                                )
                        );

        if (ride.getStatus()
                != RideStatus.COMPLETED) {

            throw new InvalidOperationException(
                    "Only a completed ride can be rated"
            );
        }

        if (ride.getDriver() == null) {

            throw new InvalidOperationException(
                    "Completed ride does not have an assigned driver"
            );
        }

        if (rideRatingRepository
                .existsByRideId(
                        ride.getId()
                )) {

            throw new ConflictException(
                    "This ride has already been rated"
            );
        }

        Driver driver =
                ride.getDriver();

        TaxiCompany company =
                ride.getCompany();

        RideRating rating =
                new RideRating();

        rating.setRide(
                ride
        );

        rating.setCustomer(
                customer
        );

        rating.setDriver(
                driver
        );

        rating.setCompany(
                company
        );

        rating.setDriverRating(
                request.driverRating()
        );

        rating.setCompanyRating(
                request.companyRating()
        );

        rating.setComment(
                normalize(
                        request.comment()
                )
        );

        RideRating savedRating =
                rideRatingRepository.saveAndFlush(
                        rating
                );

        updateDriverRating(
                driver
        );

        updateCompanyRating(
                company
        );

        /*
         * In the same transaction as the rating, deliberately.
         *
         * A company that is never told about a rating cannot answer it, and
         * the aggregate on their profile moves with no explanation attached.
         * Writing it here means a notification exists for exactly the ratings
         * that exist — no delivery job to fall behind, and nothing to
         * reconcile later.
         */
        notificationService.rideRated(
                company,
                ride.getId(),
                savedRating.getDriverRating(),
                savedRating.getCompanyRating()
        );

        return mapToResponse(
                savedRating
        );
    }

    private void updateDriverRating(
            Driver driver
    ) {

        Double average =
                rideRatingRepository
                        .calculateAverageDriverRating(
                                driver.getId()
                        );

        long count =
                rideRatingRepository
                        .countByDriverId(
                                driver.getId()
                        );

        driver.setRating(
                roundRating(average)
        );

        driver.setRatingCount(
                Math.toIntExact(count)
        );

        driverRepository.save(
                driver
        );
    }

    private void updateCompanyRating(
            TaxiCompany company
    ) {

        Double average =
                rideRatingRepository
                        .calculateAverageCompanyRating(
                                company.getId()
                        );

        long count =
                rideRatingRepository
                        .countByCompanyId(
                                company.getId()
                        );

        company.setRating(
                roundRating(average)
        );

        company.setRatingCount(
                Math.toIntExact(count)
        );

        taxiCompanyRepository.save(
                company
        );
    }

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
                    "Only customers can rate rides"
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

    private String normalize(
            String value
    ) {

        if (value == null
                || value.isBlank()) {

            return null;
        }

        return value.trim();
    }

    private Double roundRating(
            Double value
    ) {

        if (value == null) {
            return null;
        }

        return Math.round(
                value * 100.0
        ) / 100.0;
    }

    private RideRatingResponse mapToResponse(
            RideRating rating
    ) {

        Driver driver =
                rating.getDriver();

        TaxiCompany company =
                rating.getCompany();

        return new RideRatingResponse(
                rating.getId(),
                rating.getRide().getId(),
                driver.getId(),
                driver.getFirstName()
                        + " "
                        + driver.getLastName(),
                rating.getDriverRating(),
                company.getId(),
                company.getDisplayName(),
                rating.getCompanyRating(),
                rating.getComment(),
                rating.getCreatedAt()
        );
    }
}