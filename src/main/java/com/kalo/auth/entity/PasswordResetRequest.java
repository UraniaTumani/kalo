package com.kalo.auth.entity;

import com.kalo.auth.enums.PasswordResetChannel;
import com.kalo.auth.enums.PasswordResetStatus;
import com.kalo.common.entity.BaseEntity;
import com.kalo.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One attempt to recover a password.
 *
 * A request now always carries a code, which is the shape change an OTP forces:
 * the old design created the row first and minted the code later, because
 * nothing could be delivered until an administrator had telephoned the account
 * holder. A message sent to the number on the account removes that gap, so
 * {@code codeHash} is written at creation and only ever cleared once the code
 * has been spent.
 *
 * It stays nullable all the same, for two reasons: a redeemed request clears it
 * rather than keeping a spent secret around, and rows created under the old
 * design never had one.
 */
@Entity
@Table(name = "password_reset_requests")
@Getter
@Setter
public class PasswordResetRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PasswordResetStatus status;

    /** Whether the code was texted to the account, or vouched for by a person. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PasswordResetChannel channel = PasswordResetChannel.SMS;

    /**
     * Bcrypt, and now more load-bearing than it was.
     *
     * The old code was eight characters of a 27-letter alphabet, about forty
     * bits. Six digits is twenty — a million possibilities, which a GPU walks
     * through instantly. So if this table ever leaks, bcrypt's work factor is
     * the only thing between an attacker and every live code in it; a fast hash
     * here would be equivalent to storing them in plaintext.
     *
     * {@link #attempts} bounds the online guess, this bounds the offline one,
     * and neither is optional now that the code is this short.
     */
    @Column(name = "code_hash", length = 255)
    private String codeHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Wrong codes tried against this request. Burnt past the cap. */
    @Column(nullable = false)
    private int attempts = 0;

    /**
     * The administrator who vouched for the account holder.
     *
     * Null for every SMS recovery, which is the normal case — the message going
     * to the number on the account is the verification, and no person is
     * involved. Set only on {@link PasswordResetChannel#ADMIN_FALLBACK}, where
     * it is the whole accountability.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_user_id")
    private User issuedBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

    /**
     * How the administrator established who they were talking to.
     *
     * Free text, mandatory on the fallback, and the reason the fallback is
     * auditable rather than merely logged: "called the office line and
     * confirmed the last three rides" is reviewable months later, and a
     * required field is a prompt to actually do the checking.
     */
    @Column(name = "verification_note", length = 500)
    private String verificationNote;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Whether a code presented now could still be redeemed.
     *
     * Deliberately one question rather than several: callers must not be able
     * to tell an expired request from a used one from a refused one, because
     * that difference is only ever useful to somebody probing.
     */
    public boolean isRedeemable(Instant now) {
        return status == PasswordResetStatus.ACTIVE
                && codeHash != null
                && expiresAt != null
                && expiresAt.isAfter(now);
    }
}
