package com.kalo.auth.dto;

import java.time.Instant;

/**
 * The one and only time a code exists outside the holder's head.
 *
 * Returned from the admin fallback alone. The normal path never produces this:
 * the code goes to the number on the account and no response anywhere carries
 * it. Only a bcrypt hash is kept, so this cannot be reproduced — losing it
 * means issuing another, which is deliberate.
 */
public record IssuedResetCodeResponse(
        Long requestId,
        String code,
        Instant expiresAt
) {
}
