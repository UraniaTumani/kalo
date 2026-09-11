package com.kalo.ride.dto;

import com.kalo.partner.enums.PaymentMethod;
import com.kalo.vehicle.enums.VehicleType;

import java.util.Set;

public record TaxiOptionResponse(

        Long offerId,

        Long companyId,

        String companyName,

        Double companyRating,

        Integer companyRatingCount,

        Double distanceKm,

        Long nearestDriverId,

        Double driverRating,

        Integer driverRatingCount,

        Long vehicleId,

        String plateNumber,

        String vehicleBrand,

        String vehicleModel,

        VehicleType vehicleType,

        Set<PaymentMethod> paymentMethods,

        String pricingNote

) {
}