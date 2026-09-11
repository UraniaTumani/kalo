package com.kalo.partner.dto;

import com.kalo.partner.enums.PaymentMethod;

import java.util.Set;

public record OperationalSettingsResponse(

        Long companyId,

        String companyName,

        boolean bookingEnabled,

        Set<PaymentMethod> paymentMethods

) {
}