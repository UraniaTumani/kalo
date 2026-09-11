package com.kalo.admin.dto;

import com.kalo.ride.enums.RideStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminRideDetailResponse(

        Long rideId,

        Long rideRequestId,

        Long customerId,

        String customerName,

        String customerPhone,

        Long companyId,

        String companyName,

        Long driverId,

        String driverName,

        Long vehicleId,

        String vehiclePlateNumber,

        RideStatus status,

        Double pickupLatitude,

        Double pickupLongitude,

        String pickupAddress,

        Double destinationLatitude,

        Double destinationLongitude,

        String destinationAddress,

        Instant requestedAt,

        Instant acceptedAt,

        Instant declinedAt,

        Instant cancelledAt,

        Instant driverArrivingAt,

        Instant driverArrivedAt,

        Instant startedAt,

        Instant completedAt,

        BigDecimal finalAmount

) {
}
