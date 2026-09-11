package com.kalo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Phone is deliberately absent: it is the login identifier and is unique, so
 * changing it would silently invalidate the user's own credentials and any
 * token they hold. Changing a phone number needs a verification flow, which is
 * not part of the MVP.
 */
public record UpdateProfileRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName,

        @Email(message = "Email is not valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email

) {
}
