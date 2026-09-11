package com.kalo.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PartnerRegisterRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100)
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        String lastName,

        @NotBlank(message = "Phone is required")
        @Size(max = 30)
        String phone,

        @Email(message = "Email is not valid")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Password is required")
        @Size(
                min = 8,
                max = 100,
                message = "Password must contain between 8 and 100 characters"
        )
        String password,

        @NotBlank(message = "Legal company name is required")
        @Size(max = 200)
        String legalName,

        @NotBlank(message = "Display name is required")
        @Size(max = 150)
        String displayName,

        @NotBlank(message = "NIPT is required")
        @Size(max = 30)
        String nipt,

        @NotBlank(message = "Company address is required")
        @Size(max = 500)
        String address

) {
}