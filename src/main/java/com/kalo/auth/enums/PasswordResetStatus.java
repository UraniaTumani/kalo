package com.kalo.auth.enums;

/**
 * Where a password recovery has got to.
 *
 * The order matters: a request is only ever useful in ISSUED, and every other
 * state is terminal. Nothing moves backwards.
 */
public enum PasswordResetStatus {

    /**
     * Someone asked. Nobody has been identified yet and no code exists, so
     * this state grants nothing at all.
     */
    PENDING,

    /**
     * An administrator rang the number on the account, satisfied themselves it
     * was the right person, and minted a code. Usable until it expires.
     */
    ISSUED,

    /** The code was redeemed and the password changed. */
    USED,

    /**
     * Refused, or burnt. Covers three different endings that all mean the same
     * thing to the holder: an administrator declined it, too many wrong codes
     * were tried, or the account was suspended when the request arrived.
     */
    REJECTED
}
