package com.kalo.support.dto;

import com.kalo.support.enums.SupportCategory;
import com.kalo.support.enums.SupportStatus;
import com.kalo.user.enums.UserRole;

import java.time.Instant;

/**
 * The admin view: the submitter's own view plus who sent it.
 *
 * A separate record rather than extra nullable fields on the shared one, so
 * that the submitter's name and phone can only ever reach an endpoint behind
 * the ADMIN role — the compiler stops the leak, not a code review.
 */
public record AdminSupportRequestResponse(

        Long id,

        Long userId,

        String userFirstName,

        String userLastName,

        String userPhone,

        String userEmail,

        UserRole role,

        SupportCategory category,

        String subject,

        String message,

        SupportStatus status,

        Instant createdAt,

        Instant updatedAt

) {
}
