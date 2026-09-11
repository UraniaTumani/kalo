package com.kalo.rating.dto;

import java.time.Instant;

public record RideRatingResponse(

        Long ratingId,

        Long rideId,

        Long driverId,

        String driverName,

        int driverRating,

        Long companyId,

        String companyName,

        int companyRating,

        String comment,

        Instant createdAt

) {
}