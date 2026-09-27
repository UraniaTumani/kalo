package com.kalo.auth.enums;

/**
 * Where a password recovery has got to.
 *
 * Three states, all terminal except the first, and nothing moves backwards.
 *
 * There used to be four. PENDING and ISSUED existed because a code was minted
 * only after an administrator had telephoned the account holder, so a request
 * spent time in the system granting nothing at all. An OTP has no such gap: the
 * code exists the moment the request does, so the waiting state has nothing
 * left to describe. Migration 024 closes the rows that were still in it.
 */
public enum PasswordResetStatus {

    /**
     * A code exists and could still be redeemed, subject to its expiry and its
     * remaining attempts. The only state that grants anything.
     */
    ACTIVE,

    /** The code was redeemed and the password changed. */
    USED,

    /**
     * Refused, or burnt, or superseded. Four different endings that all mean
     * the same thing to whoever holds the code: too many wrong guesses, the
     * account was suspended, a newer request replaced this one, or an
     * administrator closed it.
     */
    REJECTED
}
