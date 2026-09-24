package com.kalo.auth.dto;

import java.time.Instant;

/**
 * The one and only time the code exists outside the caller's head.
 *
 * Returned to the administrator who has just identified the account holder, to
 * be read down the telephone. Only a bcrypt hash is kept, so this response
 * cannot be reproduced — losing it means issuing a fresh code, by design.
 */
public record IssuedResetCodeResponse(
        Long requestId,
        String code,
        Instant expiresAt
) {
}
