package com.kalo.auth.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A long-lived credential that buys a new access token.
 *
 * Only the SHA-256 hash of the token is stored. The raw value is returned to
 * the client once, at issue, and never again — so a leak of this table hands
 * over no live sessions, the same reasoning that applies to password hashes.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Set when the token is rotated away, when the user signs out, or when
     * every session is dropped. Null means the token is still usable.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
