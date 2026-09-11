package com.kalo.rating.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateRideRatingRequest(

        @Min(
                value = 1,
                message = "Driver rating must be at least 1"
        )
        @Max(
                value = 5,
                message = "Driver rating must be at most 5"
        )
        int driverRating,

        @Min(
                value = 1,
                message = "Company rating must be at least 1"
        )
        @Max(
                value = 5,
                message = "Company rating must be at most 5"
        )
        int companyRating,

        @Size(
                max = 1000,
                message = "Comment cannot exceed 1000 characters"
        )
        String comment

) {
}