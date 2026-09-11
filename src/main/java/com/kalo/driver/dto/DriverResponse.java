package com.kalo.driver.dto;

import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;

import java.time.LocalDate;

public record DriverResponse(

        Long id,

        String firstName,

        String lastName,

        String phone,

        String licenseNumber,

        LocalDate licenseExpiryDate,

        LocalDate dateOfBirth,

        Double rating,

        DriverStatus status,

        DriverAvailabilityStatus availabilityStatus

) {
}