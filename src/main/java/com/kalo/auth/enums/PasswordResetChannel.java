package com.kalo.auth.enums;

/**
 * How the code for a recovery reached the person using it.
 *
 * Recorded because the two are not equally trustworthy and should not be
 * equally common. An SMS code proves possession of the SIM on the account. A
 * fallback code proves that an administrator was satisfied by something else,
 * which is a judgement rather than a fact — so it names who made it, when, and
 * on what grounds, and a run of them is something worth noticing.
 */
public enum PasswordResetChannel {

    /** Sent to the number on the account. The normal path. */
    SMS,

    /**
     * Issued by an administrator to somebody who has permanently lost the
     * number. The exception, and audited as one.
     */
    ADMIN_FALLBACK
}
