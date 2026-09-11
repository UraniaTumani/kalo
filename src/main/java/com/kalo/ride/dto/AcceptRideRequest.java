package com.kalo.ride.dto;

import jakarta.validation.constraints.NotNull;

public record AcceptRideRequest(

        @NotNull(message = "Driver id is required")
        Long driverId

) {
}