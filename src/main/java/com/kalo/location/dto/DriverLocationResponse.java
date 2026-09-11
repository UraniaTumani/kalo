package com.kalo.location.dto;

import java.time.Instant;

public record DriverLocationResponse(

        Long driverId,

        Double latitude,

        Double longitude,

        Instant locationUpdatedAt

) {
}