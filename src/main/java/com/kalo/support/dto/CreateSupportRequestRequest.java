package com.kalo.support.dto;

import com.kalo.support.enums.SupportCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Note what is absent: no user id and no role. Both are taken from the
 * authenticated caller, so there is no field here a client could set to file a
 * request as somebody else.
 */
public record CreateSupportRequestRequest(

        @NotNull(
                message = "Category is required"
        )
        SupportCategory category,

        @NotBlank(
                message = "Subject is required"
        )
        @Size(
                max = 150,
                message = "Subject cannot exceed 150 characters"
        )
        String subject,

        @NotBlank(
                message = "Message is required"
        )
        @Size(
                max = 4000,
                message = "Message cannot exceed 4000 characters"
        )
        String message

) {
}
