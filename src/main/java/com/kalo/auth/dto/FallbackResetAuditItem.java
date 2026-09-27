package com.kalo.auth.dto;

import java.time.Instant;

/**
 * One administrator-issued recovery, as the audit trail shows it.
 *
 * Carries who it was for, who allowed it, when, on what grounds, and what
 * became of it. No code and no hash: the trail is about the decision, and a
 * spent secret in a listing endpoint would be a secret in a browser cache.
 */
public record FallbackResetAuditItem(
        Long id,
        String personName,
        String personPhone,
        String issuedByName,
        String verificationNote,
        Instant issuedAt,
        String status
) {
}
