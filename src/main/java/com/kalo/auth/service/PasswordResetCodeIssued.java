package com.kalo.auth.service;

import java.time.Instant;

/**
 * A code has been minted and needs sending.
 *
 * An event rather than a direct call, for one reason: the send has to happen
 * after the transaction commits and on another thread, and an event is how
 * Spring expresses both without the service having to know about either.
 *
 * Carries the raw code, which never goes anywhere else. It is not stored — only
 * a bcrypt hash is — it is not returned in a response, and it is not logged.
 * This object exists in memory for as long as it takes
 * {@link PasswordResetSmsDispatcher} to hand it to a provider.
 */
record PasswordResetCodeIssued(
        Long userId,
        String phone,
        String rawCode,
        Instant expiresAt
) {
}
