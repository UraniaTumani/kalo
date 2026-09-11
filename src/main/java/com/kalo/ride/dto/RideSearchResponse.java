package com.kalo.ride.dto;

import com.kalo.ride.enums.RideRequestStatus;

import java.time.Instant;
import java.util.List;

public record RideSearchResponse(

        Long rideRequestId,

        RideRequestStatus status,

        Instant expiresAt,

        List<TaxiOptionResponse> taxiOptions

) {
}