package com.kalo.driver.service;
import com.kalo.driver.entity.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
class DriverInvitationDeliveryTest {
    private final Instant now = Instant.parse("2026-10-24T18:00:00Z");
    private DriverInvitation invitation = invitation();
    private DriverInvitation invitation() {
        var i = new DriverInvitation(); i.setExpiresAt(now.plusSeconds(86400)); return i;
    }
    private DriverInvitationDelivery queue() {
        return new DriverInvitationDelivery(invitation, "+355690000001", "test-key", "encrypted", now);
    }
    @Test void pendingQueueIsDueAndBackoffPreventsEarlyRetry() {
        var q = queue(); assertThat(q.canAttempt(now)).isTrue(); q.recordFailure(now);
        assertThat(q.getNextAttemptAt()).isEqualTo(now.plusSeconds(60));
        assertThat(q.canAttempt(now.plusSeconds(59))).isFalse();
        assertThat(q.canAttempt(now.plusSeconds(60))).isTrue();
        assertThatThrownBy(() -> q.recordFailure(now)).isInstanceOf(IllegalStateException.class);
    }
    @Test void fifthFailureStopsRetriesAndPurgesPayload() {
        var q = queue();
        for (int n = 0; n < 5; n++) q.recordFailure(q.getNextAttemptAt());
        assertThat(q.getAttempts()).isEqualTo(5);
        assertThat(q.getState()).isEqualTo(DriverInvitationDelivery.State.FAILED);
        assertThat(q.getEncryptedPayload()).isNull(); assertThat(q.canAttempt(now.plusSeconds(5000))).isFalse();
    }
    @Test void providerAcceptanceIsTerminalAndPurgesPayload() {
        var q = queue(); q.recordAccepted(now);
        assertThat(q.getState()).isEqualTo(DriverInvitationDelivery.State.ACCEPTED);
        assertThat(q.getAttempts()).isEqualTo(1); assertThat(q.getEncryptedPayload()).isNull();
        assertThatThrownBy(() -> q.recordAccepted(now)).isInstanceOf(IllegalStateException.class);
    }
    @Test void revokedRedeemedAndExpiredInvitationsCannotBeAttempted() {
        var q = queue(); invitation.setRevokedAt(now); assertThat(q.canAttempt(now)).isFalse();
        invitation.setRevokedAt(null); invitation.setRedeemedAt(now); assertThat(q.canAttempt(now)).isFalse();
        invitation.setRedeemedAt(null); invitation.setExpiresAt(now); assertThat(q.canAttempt(now)).isFalse();
    }
    @Test void retryBeyondExpiryCancelsAndPurgesPayload() {
        invitation.setExpiresAt(now.plusSeconds(60)); var q = queue(); q.recordFailure(now);
        assertThat(q.getState()).isEqualTo(DriverInvitationDelivery.State.CANCELLED);
        assertThat(q.getEncryptedPayload()).isNull();
    }
    @Test void cancellationIsIdempotentAndDoesNotOverwriteAcceptance() {
        var q = queue(); q.cancel(); q.cancel(); assertThat(q.getEncryptedPayload()).isNull();
        var accepted = queue(); accepted.recordAccepted(now); accepted.cancel();
        assertThat(accepted.getState()).isEqualTo(DriverInvitationDelivery.State.ACCEPTED);
    }
    @Test void reviewRequiresProviderIdentityAndDoesNotAllowFurtherAttempts() {
        var q = queue();
        assertThatThrownBy(() -> q.requireReview(now)).isInstanceOf(IllegalStateException.class);
        q.recordProviderMessageId("00000000-0000-0000-0000-000000000001"); q.requireReview(now);
        assertThat(q.getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
        assertThat(q.canAttempt(now.plusSeconds(1000))).isFalse();
        assertThat(q.getEncryptedPayload()).isNull(); assertThat(q.getAttempts()).isEqualTo(1);
    }
    @Test void providerIdentityCannotChangeBetweenAttempts() {
        var q = queue(); String id = "00000000-0000-0000-0000-000000000001";
        q.recordProviderMessageId(id); q.recordProviderMessageId(id);
        assertThatThrownBy(() -> q.recordProviderMessageId("00000000-0000-0000-0000-000000000002"))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
