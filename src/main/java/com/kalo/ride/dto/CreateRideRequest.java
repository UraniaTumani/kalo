package com.kalo.ride.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRideRequest(

        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double pickupLatitude,

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double pickupLongitude,

        @Size(max = 500)
        String pickupAddress,

        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double destinationLatitude,

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double destinationLongitude,

        @Size(max = 500)
        String destinationAddress

) {
}