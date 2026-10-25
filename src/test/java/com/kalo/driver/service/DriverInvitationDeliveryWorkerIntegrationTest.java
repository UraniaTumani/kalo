package com.kalo.driver.service;

import com.kalo.driver.entity.*;
import com.kalo.driver.enums.*;
import com.kalo.driver.repository.*;
import com.kalo.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriverInvitationDeliveryWorkerIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager entities;
    @Autowired DriverRepository drivers;
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired DriverInvitationEligibilityPolicy eligibility;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DriverInvitationDeliveryWorker disabledWorker;
    private final DriverInvitationTokenCodec codec = new DriverInvitationTokenCodec();
    private final DriverInvitationDeliveryCipher cipher = new DriverInvitationDeliveryCipher(new byte[32]);
    private final DriverInvitationTransport transport = mock(DriverInvitationTransport.class);
    private Long queueId;
    private Long invitationId;
    private Long driverId;
    private Long companyId;

    @BeforeEach
    void prepareQueue() {
        var company = fixtures.approvedCompany(); companyId = company.getId();
        var driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        driverId = driver.getId();
        var invitation = new DriverInvitation(); invitation.setDriver(driver); invitation.setCompany(company);
        String token = codec.generate(); invitation.setTokenHash(codec.digest(token));
        invitation.setExpiresAt(Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS));
        invitations.saveAndFlush(invitation); invitationId = invitation.getId();
        String encrypted = cipher.encrypt(token, invitationId, driverId, driver.getPhone(), invitation.getExpiresAt());
        queueId = deliveries.saveAndFlush(new DriverInvitationDelivery(invitation, driver.getPhone(),
                "test-key", encrypted, Instant.now().minusSeconds(1))).getId();
    }

    @SuppressWarnings("unchecked")
    private DriverInvitationDeliveryWorker worker(boolean withCipher, boolean withTransport, String key) {
        ObjectProvider<DriverInvitationDeliveryCipher> keys = mock(ObjectProvider.class);
        ObjectProvider<DriverInvitationTransport> senders = mock(ObjectProvider.class);
        when(keys.getIfAvailable()).thenReturn(withCipher ? cipher : null);
        when(senders.getIfAvailable()).thenReturn(withTransport ? transport : null);
        return new DriverInvitationDeliveryWorker(jdbcTemplate, entities, drivers, deliveries, eligibility,
                keys, senders, key, true);
    }
    private DriverInvitationDeliveryWorker.Result process() {
        return new TransactionTemplate(transactions).execute(s -> worker(true, true, "test-key").process(queueId));
    }
    private DriverInvitationDelivery row() { return deliveries.findById(queueId).orElseThrow(); }

    @Test void disabledWorkerAndMissingDependenciesNeverSend() {
        assertThat(disabledWorker.process(queueId)).isEqualTo(DriverInvitationDeliveryWorker.Result.DISABLED);
        for (var w : new DriverInvitationDeliveryWorker[]{worker(false,true,"test-key"), worker(true,false,"test-key")}) {
            DriverInvitationDeliveryWorker.Result result =
                    new TransactionTemplate(transactions).execute(s -> w.process(queueId));
            assertThat(result).isEqualTo(DriverInvitationDeliveryWorker.Result.DISABLED);
        }
        verifyNoInteractions(transport); assertThat(row().getAttempts()).isZero();
    }
    @Test void acceptancePurgesPayloadAndRepeatedProcessingDoesNotSendAgain() {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(DriverInvitationTransport.Outcome.ACCEPTED);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.ACCEPTED);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.SKIPPED);
        verify(transport).send(anyString(), anyString(), eq("driver-invitation-" + invitationId));
        assertThat(row().getEncryptedPayload()).isNull(); assertThat(row().getAttempts()).isEqualTo(1);
    }
    @Test void transientFailureSchedulesRetryAndEarlyPollingDoesNotSpendAttempt() {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(DriverInvitationTransport.Outcome.RETRYABLE_FAILURE);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.RETRY_SCHEDULED);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.SKIPPED);
        assertThat(row().getAttempts()).isEqualTo(1); verify(transport).send(anyString(), anyString(), anyString());
    }
    @Test void permanentFailureStopsImmediatelyAndPurgesPayload() {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(DriverInvitationTransport.Outcome.PERMANENT_FAILURE);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.FAILED);
        assertThat(row().getEncryptedPayload()).isNull(); assertThat(row().getAttempts()).isEqualTo(1);
    }
    @Test void providerExceptionRequiresReviewWithoutPropagatingItsContents() {
        when(transport.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("sensitive provider payload"));
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.REVIEW_REQUIRED);
        assertThat(row().getAttempts()).isEqualTo(1);
    }
    @Test void revokedInvitationIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE driver_invitations SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", invitationId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED);
        assertThat(row().getEncryptedPayload()).isNull(); verifyNoInteractions(transport);
    }
    @Test void suspendedCompanyIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE taxi_companies SET status = 'SUSPENDED' WHERE id = ?", companyId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED); verifyNoInteractions(transport);
    }
    @Test void changedDestinationIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE drivers SET phone = '+355690000009' WHERE id = ?", driverId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED); verifyNoInteractions(transport);
    }
    @Test void tamperedPayloadIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE driver_invitation_deliveries SET encrypted_payload = 'invalid' WHERE id = ?", queueId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED);
        assertThat(row().getEncryptedPayload()).isNull(); verifyNoInteractions(transport);
    }
    @Test void unknownKeyVersionIsHeldWithoutConsumingAttempt() {
        DriverInvitationDeliveryWorker.Result result = new TransactionTemplate(transactions)
                .execute(s -> worker(true,true,"other-key").process(queueId));
        assertThat(result).isEqualTo(DriverInvitationDeliveryWorker.Result.DISABLED);
        assertThat(row().getState()).isEqualTo(DriverInvitationDelivery.State.PENDING);
        assertThat(row().getAttempts()).isZero(); verifyNoInteractions(transport);
    }
    @Test void expiredInvitationIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE driver_invitations SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", invitationId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED); verifyNoInteractions(transport);
    }
    @Test void redeemedInvitationIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE driver_invitations SET redeemed_at = CURRENT_TIMESTAMP WHERE id = ?", invitationId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED); verifyNoInteractions(transport);
    }
    @Test void inactiveDriverIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE drivers SET status = 'INACTIVE' WHERE id = ?", driverId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED); verifyNoInteractions(transport);
    }
    @Test void tokenDigestMismatchIsCancelledBeforeSending() {
        jdbcTemplate.update("UPDATE driver_invitations SET token_hash = ? WHERE id = ?", "a".repeat(64), invitationId);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.CANCELLED); verifyNoInteractions(transport);
    }
    @Test void fiveFailuresUseSameIdempotencyKeyThenStop() {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(DriverInvitationTransport.Outcome.RETRYABLE_FAILURE);
        for (int n = 0; n < 5; n++) {
            jdbcTemplate.update("UPDATE driver_invitation_deliveries SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", queueId);
            assertThat(process()).isEqualTo(n == 4 ? DriverInvitationDeliveryWorker.Result.FAILED
                    : DriverInvitationDeliveryWorker.Result.RETRY_SCHEDULED);
        }
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.SKIPPED);
        verify(transport, times(5)).send(anyString(), anyString(), eq("driver-invitation-" + invitationId));
        assertThat(row().getEncryptedPayload()).isNull(); assertThat(row().getAttempts()).isEqualTo(5);
    }
    @Test void concurrentWorkersDoNotSendAcceptedEntryTwice() throws Exception {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(DriverInvitationTransport.Outcome.ACCEPTED);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<DriverInvitationDeliveryWorker.Result> attempt = () -> {
            ready.countDown();
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
            return process();
        };
        try {
            var one = executor.submit(attempt); var two = executor.submit(attempt);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(java.util.List.of(one.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    two.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(DriverInvitationDeliveryWorker.Result.ACCEPTED, DriverInvitationDeliveryWorker.Result.SKIPPED);
            verify(transport).send(anyString(), anyString(), anyString());
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void uncertainResultRequiresReviewAndCannotBeSentAgain() {
        when(transport.send(anyString(),anyString(),anyString())).thenReturn(DriverInvitationTransport.Outcome.REVIEW_REQUIRED);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.REVIEW_REQUIRED);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.SKIPPED);
        assertThat(row().getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
        assertThat(row().getEncryptedPayload()).isNull(); assertThat(row().getProviderMessageId()).isNotBlank();
        verify(transport).send(anyString(),anyString(),anyString());
    }
    @Test void missingProviderOutcomeRequiresReviewRatherThanBlindRetry() {
        when(transport.send(anyString(),anyString(),anyString())).thenReturn(null);
        assertThat(process()).isEqualTo(DriverInvitationDeliveryWorker.Result.REVIEW_REQUIRED);
        assertThat(row().getAttempts()).isEqualTo(1);
    }

}
