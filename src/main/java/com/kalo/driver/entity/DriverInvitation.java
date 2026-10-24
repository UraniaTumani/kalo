package com.kalo.driver.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.partner.entity.TaxiCompany;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A pending invitation for a driver to create an individual login.
 *
 * Only a digest of a high-entropy, single-use invitation token belongs in
 * persistent storage. No delivery or redemption API is enabled yet.
 */
@Getter
@Setter
@Entity
@Table(name = "driver_invitations", indexes = {
        @Index(name = "idx_driver_invitations_driver_created",
                columnList = "driver_id, created_at")
})
public class DriverInvitation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_driver_invitations_driver"))
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_driver_invitations_company"))
    private TaxiCompany company;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isRedeemable(Instant now) {
        return redeemedAt == null && revokedAt == null
                && expiresAt != null && expiresAt.isAfter(now);
    }
}
