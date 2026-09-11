package com.kalo.ride.dto;

import com.kalo.ride.enums.RideStatus;

import java.time.Instant;

public record PartnerRideResponse(

        Long rideId,

        String customerFirstName,

        String customerLastName,

        String customerPhone,

        Double pickupLatitude,

        Double pickupLongitude,

        String pickupAddress,

        Double destinationLatitude,

        Double destinationLongitude,

        String destinationAddress,

        RideStatus status,

        Instant requestedAt

) {
}