package com.kalo.auth.dto;

import java.time.Instant;

/**
 * A pending recovery as the administrator working the queue sees it.
 *
 * Carries the phone in full, because ringing it is the whole verification —
 * and the name and role, so the caller can be asked something only the account
 * holder would know before a code is minted.
 */
public record PasswordResetQueueItem(
        Long id,
        String firstName,
        String lastName,
        String phone,
        String role,
        String userStatus,
        Instant requestedAt
) {
}
