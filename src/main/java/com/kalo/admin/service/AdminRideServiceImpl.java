package com.kalo.admin.service;

import com.kalo.admin.dto.AdminRideDetailResponse;
import com.kalo.admin.dto.AdminRideResponse;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.entity.RideRequest;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.user.entity.User;
import com.kalo.vehicle.entity.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRideServiceImpl
        implements AdminRideService {

    private final RideRepository rideRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminRideResponse> getRides(
            RideStatus status,
            Long companyId,
            Pageable pageable
    ) {

        return rideRepository
                .findAllForAdmin(
                        status,
                        companyId,
                        pageable
                )
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminRideDetailResponse getRideById(
            Long rideId
    ) {

        Ride ride =
                rideRepository
                        .findById(rideId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Ride not found"
                                )
                        );

        RideRequest rideRequest =
                ride.getRideRequest();

        User customer =
                ride.getCustomer();

        Driver driver =
                ride.getDriver();

        Vehicle vehicle =
                ride.getVehicle();

        return new AdminRideDetailResponse(
                ride.getId(),
                rideRequest.getId(),
                customer.getId(),
                fullName(
                        customer.getFirstName(),
                        customer.getLastName()
                ),
                customer.getPhone(),
                ride.getCompany().getId(),
                ride.getCompany().getDisplayName(),

                driver == null ? null : driver.getId(),
                driver == null
                        ? null
                        : fullName(
                                driver.getFirstName(),
                                driver.getLastName()
                        ),

                vehicle == null ? null : vehicle.getId(),
                vehicle == null ? null : vehicle.getPlateNumber(),

                ride.getStatus(),
                rideRequest.getPickupLatitude(),
                rideRequest.getPickupLongitude(),
                rideRequest.getPickupAddress(),
                rideRequest.getDestinationLatitude(),
                rideRequest.getDestinationLongitude(),
                rideRequest.getDestinationAddress(),
                ride.getRequestedAt(),
                ride.getAcceptedAt(),
                ride.getDeclinedAt(),
                ride.getCancelledAt(),
                ride.getDriverArrivingAt(),
                ride.getDriverArrivedAt(),
                ride.getStartedAt(),
                ride.getCompletedAt(),
                ride.getFinalAmount()
        );
    }

    private AdminRideResponse mapToResponse(
            Ride ride
    ) {

        User customer =
                ride.getCustomer();

        Driver driver =
                ride.getDriver();

        return new AdminRideResponse(
                ride.getId(),
                customer.getId(),
                fullName(
                        customer.getFirstName(),
                        customer.getLastName()
                ),
                ride.getCompany().getId(),
                ride.getCompany().getDisplayName(),

                driver == null ? null : driver.getId(),
                driver == null
                        ? null
                        : fullName(
                                driver.getFirstName(),
                                driver.getLastName()
                        ),

                ride.getStatus(),
                ride.getRequestedAt(),
                ride.getCompletedAt(),
                ride.getFinalAmount()
        );
    }

    private String fullName(
            String firstName,
            String lastName
    ) {

        return firstName + " " + lastName;
    }
}
