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

        BigDecimal finalAmount,

        /**
         * Whether this ride already carries a rating, and what it was.
         *
         * Without these the history screen had no way to tell a rated ride from
         * an unrated one: it offered a Rate button on every completed ride, and
         * the only way a passenger discovered they had already rated was to fill
         * the form in and have it refused.
         */
        boolean rated,

        Integer driverRating,

        Integer companyRating

) {
}