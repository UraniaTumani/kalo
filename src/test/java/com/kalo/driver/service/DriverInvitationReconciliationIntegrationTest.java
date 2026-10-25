package com.kalo.driver.service;
import com.kalo.driver.entity.*;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class DriverInvitationReconciliationIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationReconciliation reconciliation;
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    private static final String MESSAGE_ID = "00000000-0000-0000-0000-000000000001";
    private Long review() {
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        var invitation = new DriverInvitation(); invitation.setDriver(driver); invitation.setCompany(company);
        invitation.setTokenHash("a".repeat(64)); invitation.setExpiresAt(Instant.now().plusSeconds(3600));
        invitations.saveAndFlush(invitation);
        Instant now = Instant.now();
        var delivery = new DriverInvitationDelivery(invitation,driver.getPhone(),"test-key","encrypted",now);
        delivery.recordProviderMessageId(MESSAGE_ID); delivery.requireReview(now);
        return deliveries.saveAndFlush(delivery).getId();
    }
    @Test void reviewPersistsWithoutPayloadAndIsExcludedFromSendCandidates() {
        Long id = review(); var loaded = deliveries.findById(id).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
        assertThat(loaded.getProviderMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(loaded.getEncryptedPayload()).isNull();
        assertThat(deliveries.findDueIds(DriverInvitationDelivery.State.PENDING,"test-key",Instant.now(),PageRequest.of(0,10))).isEmpty();
    }
    @Test void confirmedAcceptanceAndRepeatedIdenticalEvidenceAreIdempotent() {
        Long id = review();
        reconciliation.applyVerifiedEvidence(id,MESSAGE_ID,DriverInvitationReconciliation.Evidence.PROVIDER_ACCEPTED);
        reconciliation.applyVerifiedEvidence(id,MESSAGE_ID,DriverInvitationReconciliation.Evidence.PROVIDER_ACCEPTED);
        assertThat(deliveries.findById(id).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.ACCEPTED);
    }
    @Test void confirmedRejectionStopsDelivery() {
        Long id = review(); reconciliation.applyVerifiedEvidence(id,MESSAGE_ID,DriverInvitationReconciliation.Evidence.PROVIDER_REJECTED);
        assertThat(deliveries.findById(id).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.FAILED);
    }
    @Test void wrongMessageIdOrMissingEvidenceCannotResolveReview() {
        Long id = review();
        assertThatThrownBy(() -> reconciliation.applyVerifiedEvidence(id,"wrong",DriverInvitationReconciliation.Evidence.PROVIDER_ACCEPTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reconciliation.applyVerifiedEvidence(id,MESSAGE_ID,null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(deliveries.findById(id).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
    }
    @Test void conflictingTerminalEvidenceDoesNotRewriteAcceptedOutcome() {
        Long id = review(); reconciliation.applyVerifiedEvidence(id,MESSAGE_ID,DriverInvitationReconciliation.Evidence.PROVIDER_ACCEPTED);
        assertThatThrownBy(() -> reconciliation.applyVerifiedEvidence(id,MESSAGE_ID,DriverInvitationReconciliation.Evidence.PROVIDER_REJECTED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(deliveries.findById(id).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.ACCEPTED);
    }
    @Test void databaseRequiresMessageIdForReviewState() {
        Long id = review();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE driver_invitation_deliveries SET provider_message_id = NULL WHERE id = ?",id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
