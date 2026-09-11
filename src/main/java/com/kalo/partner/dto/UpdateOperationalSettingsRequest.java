package com.kalo.partner.dto;

import com.kalo.partner.enums.PaymentMethod;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record UpdateOperationalSettingsRequest(

        @NotNull(message = "Booking enabled is required")
        Boolean bookingEnabled,

        @NotEmpty(
                message = "At least one payment method is required"
        )
        Set<PaymentMethod> paymentMethods

) {
}