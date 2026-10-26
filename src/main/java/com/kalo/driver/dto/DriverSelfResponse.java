package com.kalo.driver.dto;

import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;

/** Minimal account identity; no credentials, invitation data or private documents. */
public record DriverSelfResponse(Long driverId, Long companyId, String firstName,
        String lastName, String phone, DriverStatus status,
        DriverAvailabilityStatus availabilityStatus) {}
