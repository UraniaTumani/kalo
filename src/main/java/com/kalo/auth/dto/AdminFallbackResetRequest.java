package com.kalo.auth.dto;

import com.kalo.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An administrator issuing a code to somebody who has lost the number.
 *
 * The note is required, and the minimum length is the point of it rather than
 * an arbitrary bound. This endpoint exists because SMS recovery has one
 * unavoidable failure — a number that no longer reaches anybody — and the
 * danger of an endpoint like that is not that it exists but that it becomes
 * routine. A field that will not accept "ok" makes the administrator write down
 * what they actually checked, and makes it reviewable by somebody else months
 * later.
 */
public record AdminFallbackResetRequest(

        @NotBlank(message = "Phone is required")
        @Size(max = 30, message = "Phone must not exceed 30 characters")
        @Pattern(
                regexp = ValidationPatterns.PHONE,
                message = ValidationPatterns.PHONE_MESSAGE
        )
        String phone,

        @NotBlank(message = "A verification note is required")
        @Size(
                min = 20,
                max = 500,
                message = "Describe how you verified this person, in at least 20 characters"
        )
        String verificationNote
) {
}
