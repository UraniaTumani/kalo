package com.kalo.auth.entity;

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
 * One attempt to recover a password, and the record of who allowed it.
 *
 * A request carries no code when it is created. That is the point: KALO cannot
 * send anything — no SMS provider, no mail provider, and a phone number that
 * was never verified — so the only honest way to establish who is asking is
 * for an administrator to ring the number already on the account. The code is
 * minted at that moment, not before, which is why {@code codeHash} is nullable
 * and why nothing here is usable until someone has taken responsibility for it.
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

    /**
     * Bcrypt, not SHA-256.
     *
     * Refresh tokens hold 256 bits of randomness, so a fast hash costs an
     * attacker everything and there is nothing to grind. This code holds about
     * forty bits, because a person has to read it down a telephone — and forty
     * bits behind SHA-256 is a few GPU-minutes if this table ever leaks. The
     * slow hash is what makes a short code survivable; {@link #attempts} deals
     * with the online guess, this deals with the offline one.
     */
    @Column(name = "code_hash", length = 255)
    private String codeHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Wrong codes tried against this request. Burnt past the cap. */
    @Column(nullable = false)
    private int attempts = 0;

    /** The administrator who identified the caller. Null while pending. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_user_id")
    private User issuedBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

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
        return status == PasswordResetStatus.ISSUED
                && codeHash != null
                && expiresAt != null
                && expiresAt.isAfter(now);
    }
}
