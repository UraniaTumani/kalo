package com.kalo.partner.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdatePartnerProfileRequest(

        @NotBlank(message = "Legal name is required")
        @Size(max = 200)
        String legalName,

        @NotBlank(message = "Display name is required")
        @Size(max = 150)
        String displayName,

        @NotBlank(message = "Company phone is required")
        @Size(max = 30)
        String phone,

        @Email(message = "Email is not valid")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Address is required")
        @Size(max = 500)
        String address,

        @Size(max = 100)
        String licenseNumber,

        LocalDate licenseExpiryDate

) {
}