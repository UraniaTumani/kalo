package com.kalo.auth.dto;

import com.kalo.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Redeems a one-time code and sets the new password, in one call.
 *
 * Both in one request on purpose. Verifying the code first and setting the
 * password afterwards would need a second secret between the two steps — a
 * short-lived token that itself has to be single-use, hashed and expired, which
 * is the same machinery twice and one more thing that can leak. The page still
 * shows two steps; the API has one.
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

        /*
         * Six digits for the SMS code, eight characters for a fallback code
         * read down a telephone, so the bound is the looser of the two and the
         * shape is not asserted here. Checking the format would also be a
         * distinction the endpoint must not make: a malformed code and a wrong
         * one have to fail identically, and a validation error is visibly
         * different from the generic refusal.
         */
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
