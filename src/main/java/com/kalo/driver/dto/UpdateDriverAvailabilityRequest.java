package com.kalo.driver.dto;

import com.kalo.driver.enums.DriverAvailabilityStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateDriverAvailabilityRequest(

        @NotNull(message = "Availability status is required")
        DriverAvailabilityStatus availabilityStatus

) {
}