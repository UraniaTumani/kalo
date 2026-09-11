package com.kalo.ride.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CompleteRideRequest(

        @NotNull(message = "Final amount is required")
        @DecimalMin(
                value = "0.0",
                inclusive = true,
                message = "Final amount cannot be negative"
        )
        BigDecimal finalAmount

) {
}