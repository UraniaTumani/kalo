package com.kalo.common.exception;

/**
 * The caller is not authenticated for this action, and the reason is safe to
 * tell them — an expired session, a refresh token that has already been used.
 *
 * Separate from Spring's BadCredentialsException, which the handler answers
 * with "Invalid phone or password": correct for a sign-in attempt, misleading
 * for a session that simply ran out.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
