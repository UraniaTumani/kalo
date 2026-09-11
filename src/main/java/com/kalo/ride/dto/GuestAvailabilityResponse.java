package com.kalo.ride.dto;

import java.time.Instant;
import java.util.List;

public record GuestAvailabilityResponse(

        Instant checkedAt,

        int companiesAvailable,

        List<GuestTaxiOptionResponse> taxiOptions,

        String note

) {
}
