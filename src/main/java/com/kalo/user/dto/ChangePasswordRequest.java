package com.kalo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changing your own password.
 *
 * The current one is required, and not as a formality. An access token alone
 * must never be enough to take an account permanently: a token is a bearer
 * credential that can be lifted from a shared machine or a stale session,
 * whereas the password is the thing only its owner knows. Asking for it turns
 * a borrowed session into a dead end.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Current password is required")
        @Size(max = 100, message = "Current password must not exceed 100 characters")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(
                min = 8,
                max = 100,
                message = "Password must contain between 8 and 100 characters"
        )
        String newPassword
) {
}
