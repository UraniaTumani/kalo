package com.kalo.ride.dto;

import com.kalo.ride.enums.RideStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record RideResponse(

        Long rideId,

        Long rideRequestId,

        Long companyId,

        String companyName,

        Long driverId,

        Long vehicleId,

        RideStatus status,

        String pickupAddress,

        String destinationAddress,

        Instant requestedAt,

        Instant acceptedAt,

        Instant driverArrivingAt,

        Instant driverArrivedAt,

        Instant startedAt,

        Instant completedAt,

        BigDecimal finalAmount

) {
}