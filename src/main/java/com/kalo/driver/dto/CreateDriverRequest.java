package com.kalo.driver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateDriverRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100)
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        String lastName,

        @NotBlank(message = "Phone is required")
        @Size(max = 30)
        String phone,

        @NotBlank(message = "License number is required")
        @Size(max = 100)
        String licenseNumber,

        @NotNull(message = "License expiry date is required")
        LocalDate licenseExpiryDate,

        LocalDate dateOfBirth

) {
}