package com.kalo.ride.dto;

import com.kalo.partner.enums.PaymentMethod;
import com.kalo.vehicle.enums.VehicleType;

import java.util.Set;

/**
 * What an anonymous visitor is shown for one available company.
 *
 * Deliberately narrower than {@link TaxiOptionResponse}: no offer id, because
 * nothing is persisted and there is nothing to select, and no driver id, plate
 * number or vehicle id, because identifying a specific driver and car to an
 * unauthenticated caller would expose the fleet's live position to anyone.
 */
public record GuestTaxiOptionResponse(

        Long companyId,

        String companyName,

        Double companyRating,

        Integer companyRatingCount,

        Double distanceKm,

        VehicleType vehicleType,

        Set<PaymentMethod> paymentMethods,

        String pricingNote

) {
}
