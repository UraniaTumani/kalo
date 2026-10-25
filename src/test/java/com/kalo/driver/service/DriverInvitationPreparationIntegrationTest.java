package com.kalo.driver.service;
import com.kalo.driver.entity.*;
import com.kalo.driver.enums.*;
import com.kalo.driver.repository.*;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverInvitationPreparationIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationPersistence persistence;
    @Autowired DriverInvitationPreparation disabledPreparation;
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired PlatformTransactionManager transactions;
    private final DriverInvitationTokenCodec codec = new DriverInvitationTokenCodec();
    private final DriverInvitationDeliveryCipher cipher = new DriverInvitationDeliveryCipher(new byte[32]);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private Driver driver() {
        TaxiCompany company = fixtures.approvedCompany();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                company.getOwner().getPhone(), "unused", AuthorityUtils.createAuthorityList("ROLE_PARTNER")));
        return fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
    }
    @SuppressWarnings("unchecked")
    private DriverInvitationPreparation preparation(DriverInvitationDeliveryCipher encryption) {
        ObjectProvider<DriverInvitationDeliveryCipher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(encryption);
        return new DriverInvitationPreparation(persistence, invitations, deliveries, codec, provider, "test-key");
    }
    private Long prepare(Driver driver) {
        return new TransactionTemplate(transactions).execute(s -> preparation(cipher).prepare(driver.getId()));
    }
    @Test void savesMatchingHashAndEncryptedQueueAtomically() {
        Driver driver = driver(); Long id = prepare(driver);
        var invitation = invitations.findById(id).orElseThrow(); var queue = deliveries.findAll().get(0);
        assertThat(invitation.getExpiresAt().getNano() % 1000).isZero();
        String raw = cipher.decrypt(queue.getEncryptedPayload(), id, driver.getId(),
                queue.getDestination(), invitation.getExpiresAt());
        assertThat(codec.digest(raw)).isEqualTo(invitation.getTokenHash());
        assertThat(queue.getKeyId()).isEqualTo("test-key");
        assertThat(queue.getState()).isEqualTo(DriverInvitationDelivery.State.PENDING);
        assertThat(queue.getAttempts()).isZero();
    }
    @Test void missingKeyFailsClosedWithoutPersistingAnything() {
        Driver driver = driver();
        assertThatThrownBy(() -> disabledPreparation.prepare(driver.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(invitations.count()).isZero(); assertThat(deliveries.count()).isZero();
    }
    @Test void encryptionFailureRollsBackHashAndRevocation() {
        Driver driver = driver(); Long previous = prepare(driver); age(previous);
        // Invalid current destination causes encryption to fail after stageHash.
        jdbcTemplate.update("UPDATE drivers SET phone = 'invalid' WHERE id = ?", driver.getId());
        assertThatThrownBy(() -> prepare(driver)).isInstanceOf(IllegalArgumentException.class);
        assertThat(invitations.count()).isEqualTo(1);
        assertThat(invitations.findById(previous).orElseThrow().getRevokedAt()).isNull();
        assertThat(deliveries.findAll().get(0).getState()).isEqualTo(DriverInvitationDelivery.State.PENDING);
    }
    @Test void laterTransactionFailureRollsBackQueueAndInvitation() {
        Driver driver = driver();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
            preparation(cipher).prepare(driver.getId()); throw new IllegalStateException("Abort");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(invitations.count()).isZero(); assertThat(deliveries.count()).isZero();
    }
    @Test void replacementCancelsOldQueueAndPurgesItsCiphertext() {
        Driver driver = driver(); Long previous = prepare(driver); age(previous); prepare(driver);
        var queues = deliveries.findAll(); assertThat(queues).hasSize(2);
        assertThat(queues.stream().filter(q -> q.getState() == DriverInvitationDelivery.State.CANCELLED))
                .allSatisfy(q -> assertThat(q.getEncryptedPayload()).isNull()).hasSize(1);
        assertThat(invitations.findById(previous).orElseThrow().getRevokedAt()).isNotNull();
    }
    @Test void queueFailureStateAndPayloadPurgeSurviveReload() {
        Driver driver = driver(); prepare(driver); Long queueId = deliveries.findAll().get(0).getId();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var queue = deliveries.findForUpdateById(queueId).orElseThrow();
            for (int n = 0; n < 5; n++) queue.recordFailure(queue.getNextAttemptAt());
        });
        var reloaded = deliveries.findById(queueId).orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo(5);
        assertThat(reloaded.getState()).isEqualTo(DriverInvitationDelivery.State.FAILED);
        assertThat(reloaded.getEncryptedPayload()).isNull();
    }
    private void age(Long id) {
        jdbcTemplate.update("UPDATE driver_invitations SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)), id);
    }
}
