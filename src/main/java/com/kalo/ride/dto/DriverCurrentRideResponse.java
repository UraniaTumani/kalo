package com.kalo.ride.dto;

import com.kalo.ride.enums.RideStatus;
import java.time.Instant;

/** Work details only: no customer identity, phone, tokens or billing data. */
public record DriverCurrentRideResponse(
        Long rideId, RideStatus status, Long vehicleId,
        String pickupAddress, Double pickupLatitude, Double pickupLongitude,
        String destinationAddress, Double destinationLatitude, Double destinationLongitude,
        Instant acceptedAt) {}
