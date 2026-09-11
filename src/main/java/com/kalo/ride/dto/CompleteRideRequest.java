package com.kalo.ride.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CompleteRideRequest(

        /*
         * The taximeter total actually charged. A completed ride always has a
         * fare, so zero is rejected along with negative values.
         */
        @NotNull(message = "Final amount is required")
        @DecimalMin(
                value = "0.0",
                inclusive = false,
                message = "Final amount must be greater than zero"
        )
        @Digits(
                integer = 10,
                fraction = 2,
                message = "Final amount must have at most 2 decimal places"
        )
        BigDecimal finalAmount

) {
}