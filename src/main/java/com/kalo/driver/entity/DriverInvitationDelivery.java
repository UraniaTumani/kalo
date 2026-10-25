package com.kalo.driver.entity;

import com.kalo.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;

/** Encrypted queue payload; intentionally has no generated toString. */
@Getter
@Entity
@Table(name = "driver_invitation_deliveries")
public class DriverInvitationDelivery extends BaseEntity {
    public enum ReportStopReason { AGE_LIMIT, ATTEMPT_LIMIT }
    public enum State { PENDING, ACCEPTED, FAILED, CANCELLED, REVIEW }
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitation_id", nullable = false, unique = true)
    private DriverInvitation invitation;
    @Column(nullable = false, length = 30)
    private String destination;
    @Column(name = "key_id", nullable = false, length = 100)
    private String keyId;
    @Column(name = "encrypted_payload", length = 98)
    private String encryptedPayload;
    @Column(name = "provider_message_id", length = 36)
    private String providerMessageId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private State state;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "report_checks", nullable = false)
    private int reportChecks;
    @Column(name = "next_report_check_at")
    private Instant nextReportCheckAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "report_stop_reason", length = 20)
    private ReportStopReason reportStopReason;

    protected DriverInvitationDelivery() {}

    public DriverInvitationDelivery(DriverInvitation invitation, String destination,
            String keyId, String encryptedPayload, Instant now) {
        if (invitation == null || destination == null || keyId == null || keyId.isBlank()
                || keyId.length() > 100 || encryptedPayload == null || now == null) {
            throw new IllegalArgumentException("Invalid invitation delivery preparation");
        }
        this.invitation = invitation;
        this.destination = destination;
        this.keyId = keyId;
        this.encryptedPayload = encryptedPayload;
        this.state = State.PENDING;
        this.nextAttemptAt = now;
    }

    public boolean canAttempt(Instant now) {
        return state == State.PENDING && attempts < 5
                && !nextAttemptAt.isAfter(now) && invitation.isRedeemable(now);
    }

    /** Called only after an actual failed attempt; not by a polling scheduler. */
    public void recordFailure(Instant now) {
        if (!canAttempt(now)) {
            throw new IllegalStateException("Invitation delivery attempt is not allowed");
        }
        attempts++;
        if (attempts == 5) {
            finish(State.FAILED);
        } else {
            nextAttemptAt = now.plusSeconds(60L << (attempts - 1));
            if (!nextAttemptAt.isBefore(invitation.getExpiresAt())) {
                finish(State.CANCELLED);
            }
        }
    }

    /** Provider acceptance does not prove delivery to the handset. */
    public void recordAccepted(Instant now) {
        if (!canAttempt(now)) {
            throw new IllegalStateException("Invitation delivery attempt is not allowed");
        }
        attempts++;
        finish(State.ACCEPTED);
    }

    public void recordPermanentFailure(Instant now) {
        if (!canAttempt(now)) {
            throw new IllegalStateException("Invitation delivery attempt is not allowed");
        }
        attempts++;
        finish(State.FAILED);
    }

    public void recordProviderMessageId(String messageId) {
        if (messageId == null || !messageId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || (providerMessageId != null && !providerMessageId.equals(messageId))) {
            throw new IllegalArgumentException("Invalid provider message identity");
        }
        providerMessageId = messageId;
    }

    public void requireReview(Instant now) {
        if (!canAttempt(now) || providerMessageId == null) {
            throw new IllegalStateException("Invitation delivery review is not allowed");
        }
        attempts++;
        finish(State.REVIEW);
    }

    /** Caller must supply independently authenticated, correlated provider evidence. */
    public void reconcile(String messageId, boolean providerAccepted) {
        if (providerMessageId == null || !providerMessageId.equals(messageId)) {
            throw new IllegalArgumentException("Provider evidence does not match delivery");
        }
        State resolved = providerAccepted ? State.ACCEPTED : State.FAILED;
        if (state == resolved) return; // Replayed identical evidence is harmless.
        if (state != State.REVIEW) throw new IllegalStateException("Delivery is not awaiting reconciliation");
        finish(resolved);
    }

    /** Reserve a lookup durably before HTTP, so crashes still consume one bounded attempt. */
    public boolean reserveReportCheck(Instant now) {
        if (state != State.REVIEW || reportStopReason != null
                || (nextReportCheckAt != null && nextReportCheckAt.isAfter(now))) return false;
        if (getCreatedAt() == null) throw new IllegalStateException("Delivery audit timestamp is missing");
        if (!getCreatedAt().plusSeconds(47 * 3600L).isAfter(now)) {
            reportStopReason = ReportStopReason.AGE_LIMIT; nextReportCheckAt = null; return false;
        }
        if (reportChecks >= 12) {
            reportStopReason = ReportStopReason.ATTEMPT_LIMIT; nextReportCheckAt = null; return false;
        }
        reportChecks++;
        if (reportChecks == 12) {
            reportStopReason = ReportStopReason.ATTEMPT_LIMIT; nextReportCheckAt = null;
        } else {
            nextReportCheckAt = now.plusSeconds(Math.min(300L << (reportChecks - 1),21600L));
        }
        return true;
    }

    public void cancel() {
        if (state == State.PENDING) {
            finish(State.CANCELLED);
        }
    }

    private void finish(State terminal) {
        state = terminal;
        encryptedPayload = null;
        if (terminal != State.REVIEW) { nextReportCheckAt = null; reportStopReason = null; }
    }
}
