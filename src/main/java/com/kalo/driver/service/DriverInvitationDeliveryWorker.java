package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.*;
import com.kalo.driver.repository.*;
import com.kalo.partner.entity.TaxiCompany;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * One-entry worker, disabled by default; no scheduler or transport is supplied.
 * Lock order: company -> driver -> queue -> invitation. The provider call remains
 * inside this short transaction, so any future adapter MUST have bounded timeouts.
 * Retries are at-least-once; the adapter must honor the stable idempotency key.
 * Future activation/revocation must follow the same lock order.
 */
@Service
public class DriverInvitationDeliveryWorker {
    public enum Result { DISABLED, SKIPPED, CANCELLED, ACCEPTED, RETRY_SCHEDULED, FAILED, REVIEW_REQUIRED }
    private record Target(long companyId, long driverId) {}
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final DriverRepository drivers;
    private final DriverInvitationDeliveryRepository deliveries;
    private final DriverInvitationEligibilityPolicy eligibility;
    private final ObjectProvider<DriverInvitationDeliveryCipher> ciphers;
    private final ObjectProvider<DriverInvitationTransport> transports;
    private final String keyId;
    private final boolean enabled;

    public DriverInvitationDeliveryWorker(JdbcTemplate jdbc, EntityManager entities,
            DriverRepository drivers, DriverInvitationDeliveryRepository deliveries,
            DriverInvitationEligibilityPolicy eligibility,
            ObjectProvider<DriverInvitationDeliveryCipher> ciphers,
            ObjectProvider<DriverInvitationTransport> transports,
            @Value("${app.driver-invitation.delivery-key-id:}") String keyId,
            @Value("${app.driver-invitation.worker-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.entities = entities; this.drivers = drivers;
        this.deliveries = deliveries; this.eligibility = eligibility;
        this.ciphers = ciphers; this.transports = transports;
        this.keyId = keyId; this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result process(Long deliveryId) {
        if (!enabled) return Result.DISABLED;
        var cipher = ciphers.getIfAvailable();
        var transport = transports.getIfAvailable();
        if (cipher == null || transport == null || keyId == null || keyId.isBlank()) return Result.DISABLED;
        // Scalars only: do not cache queue/invitation entities before acquiring locks.
        var targets = jdbc.query("""
                SELECT i.company_id, i.driver_id FROM driver_invitation_deliveries d
                JOIN driver_invitations i ON i.id = d.invitation_id WHERE d.id = ?
                """, (rs, row) -> new Target(rs.getLong(1), rs.getLong(2)), deliveryId);
        if (targets.isEmpty()) return Result.SKIPPED;
        var target = targets.get(0);
        var company = entities.find(TaxiCompany.class, target.companyId(), LockModeType.PESSIMISTIC_WRITE);
        var driver = drivers.findForUpdateByIdAndCompanyId(target.driverId(), target.companyId()).orElse(null);
        var queue = deliveries.findForUpdateById(deliveryId).orElse(null);
        if (queue == null || queue.getState() != DriverInvitationDelivery.State.PENDING) return Result.SKIPPED;
        var invitation = queue.getInvitation();
        entities.refresh(invitation, LockModeType.PESSIMISTIC_WRITE);
        Instant now = Instant.now();
        if (!invitation.isRedeemable(now) || driver == null || company == null
                || !queue.getDestination().equals(driver.getPhone())) {
            queue.cancel(); return Result.CANCELLED;
        }
        try { eligibility.validate(driver, company); }
        catch (InvalidOperationException rejected) { queue.cancel(); return Result.CANCELLED; }
        if (!queue.canAttempt(now)) return Result.SKIPPED;
        // Unknown key versions are held for operator repair, never retried with a wrong key.
        if (!keyId.equals(queue.getKeyId())) return Result.DISABLED;
        String token;
        try {
            token = cipher.decrypt(queue.getEncryptedPayload(), invitation.getId(), driver.getId(),
                    queue.getDestination(), invitation.getExpiresAt());
            String digest = new DriverInvitationTokenCodec().digest(token);
            if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
                    invitation.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
                queue.cancel(); return Result.CANCELLED;
            }
        } catch (IllegalArgumentException invalid) { queue.cancel(); return Result.CANCELLED; }
        String requestKey = "driver-invitation-" + invitation.getId();
        queue.recordProviderMessageId(DriverInvitationMessageId.create(queue.getDestination(),
                invitation.getTokenHash(), requestKey));
        DriverInvitationTransport.Outcome outcome;
        try {
            outcome = transport.send(queue.getDestination(), token, requestKey);
        } catch (RuntimeException uncertain) {
            // The provider may have accepted: hold for reconciliation, never blindly resend.
            outcome = DriverInvitationTransport.Outcome.REVIEW_REQUIRED;
        }
        if (outcome == null || outcome == DriverInvitationTransport.Outcome.REVIEW_REQUIRED) {
            queue.requireReview(now); return Result.REVIEW_REQUIRED;
        }
        if (outcome == DriverInvitationTransport.Outcome.ACCEPTED) {
            queue.recordAccepted(now); return Result.ACCEPTED;
        }
        if (outcome == DriverInvitationTransport.Outcome.PERMANENT_FAILURE) {
            queue.recordPermanentFailure(now); return Result.FAILED;
        }
        queue.recordFailure(now);
        return queue.getState() == DriverInvitationDelivery.State.PENDING ? Result.RETRY_SCHEDULED
                : queue.getState() == DriverInvitationDelivery.State.CANCELLED ? Result.CANCELLED : Result.FAILED;
    }
}
