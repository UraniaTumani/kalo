package com.kalo.driver.service;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.repository.DriverInvitationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Internal persistence step, not a complete issuance/delivery service.
 * A future delivery orchestrator must generate the token using the codec and
 * pass only its digest here, in the same write transaction as durable delivery
 * preparation. Raw tokens must never reach API responses, logs or this entity.
 * No controller currently calls this service. Hash input must never be client supplied.
 */
@Service
public class DriverInvitationPersistence {
    private final DriverInvitationRateLimit limits;
    private final DriverInvitationRepository invitations;
    private final Duration lifetime;

    public DriverInvitationPersistence(DriverInvitationRateLimit limits,
            DriverInvitationRepository invitations,
            @Value("${app.driver-invitation.lifetime-seconds:86400}") long lifetimeSeconds) {
        if (lifetimeSeconds <= 0) {
            throw new IllegalArgumentException("Invitation lifetime must be positive");
        }
        this.limits = limits;
        this.invitations = invitations;
        this.lifetime = Duration.ofSeconds(lifetimeSeconds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long stageHash(Long driverId, String tokenHash) {
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid invitation digest");
        }
        Driver driver = limits.requireAllowedDriver(driverId);
        Instant now = Instant.now();
        invitations.revokeUnredeemedForDriver(driver.getId(), now);
        DriverInvitation invitation = new DriverInvitation();
        invitation.setDriver(driver);
        invitation.setCompany(driver.getCompany());
        // Authorization resolved and locked this company from the current PARTNER.
        // Issuer identity is never accepted as a request argument.
        invitation.recordIssuer(driver.getCompany().getOwner().getId());
        invitation.setTokenHash(tokenHash);
        // PostgreSQL timestamps have microsecond precision. Use the same exact
        // expiry before encryption and after reload: expiry is authenticated AAD.
        invitation.setExpiresAt(now.plus(lifetime).truncatedTo(ChronoUnit.MICROS));
        // Flush constraint failures before returning; revocation rolls back with insert.
        return invitations.saveAndFlush(invitation).getId();
    }
}
