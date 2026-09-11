package com.kalo.partner.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record UpdateServiceAreaRequest(

        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double latitude,

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double longitude,

        @NotNull
        @DecimalMin(
                value = "1.0",
                message = "Service radius must be at least 1 km"
        )
        @DecimalMax(
                value = "100.0",
                message = "Service radius cannot exceed 100 km"
        )
        Double radiusKm,

        @NotNull
        String timezone

) {
}