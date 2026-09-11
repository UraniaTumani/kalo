package com.kalo.ride.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record GuestAvailabilityRequest(

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude is not valid")
        @DecimalMax(value = "90.0", message = "Latitude is not valid")
        Double latitude,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude is not valid")
        @DecimalMax(value = "180.0", message = "Longitude is not valid")
        Double longitude

) {
}
