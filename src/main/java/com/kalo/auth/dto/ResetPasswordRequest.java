package com.kalo.auth.dto;

import com.kalo.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Redeems a code an administrator read out.
 *
 * The phone comes back because a bcrypt hash cannot be looked up by its own
 * value: the account is named first, then the code is checked against it.
 */
public record ResetPasswordRequest(

        @NotBlank(message = "Phone is required")
        @Size(max = 30, message = "Phone must not exceed 30 characters")
        @Pattern(
                regexp = ValidationPatterns.PHONE,
                message = ValidationPatterns.PHONE_MESSAGE
        )
        String phone,

        @NotBlank(message = "Code is required")
        @Size(max = 32, message = "Code must not exceed 32 characters")
        String code,

        @NotBlank(message = "Password is required")
        @Size(
                min = 8,
                max = 100,
                message = "Password must contain between 8 and 100 characters"
        )
        String newPassword
) {
}
