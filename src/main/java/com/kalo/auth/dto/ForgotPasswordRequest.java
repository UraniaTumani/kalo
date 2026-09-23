package com.kalo.auth.dto;

import com.kalo.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Opens a recovery. The phone is the only identifier KALO holds for everyone —
 * email is optional and, like the phone, has never been verified.
 */
public record ForgotPasswordRequest(

        @NotBlank(message = "Phone is required")
        @Size(max = 30, message = "Phone must not exceed 30 characters")
        @Pattern(
                regexp = ValidationPatterns.PHONE,
                message = ValidationPatterns.PHONE_MESSAGE
        )
        String phone
) {
}
