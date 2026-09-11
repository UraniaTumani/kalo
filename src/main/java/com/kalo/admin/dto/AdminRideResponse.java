package com.kalo.admin.dto;

import com.kalo.ride.enums.RideStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminRideResponse(

        Long rideId,

        Long customerId,

        String customerName,

        Long companyId,

        String companyName,

        Long driverId,

        String driverName,

        RideStatus status,

        Instant requestedAt,

        Instant completedAt,

        BigDecimal finalAmount

) {
}
