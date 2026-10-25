package com.kalo.driver.service;

import com.kalo.driver.entity.*;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(DriverInvitationSchedulerIntegrationTest.FakeDelivery.class)
@TestPropertySource(properties = {"app.driver-invitation.worker-enabled=true",
        "app.driver-invitation.delivery-key-id=test-key"})
class DriverInvitationSchedulerIntegrationTest extends AbstractIntegrationTest {
    @TestConfiguration
    static class FakeDelivery {
        @Bean DriverInvitationDeliveryCipher cipher() { return new DriverInvitationDeliveryCipher(new byte[32]); }
        @Bean DriverInvitationTransport transport() {
            DriverInvitationTransport transport = mock(DriverInvitationTransport.class);
            when(transport.send(anyString(),anyString(),anyString())).thenReturn(DriverInvitationTransport.Outcome.ACCEPTED);
            return transport;
        }
    }
    @Autowired DriverInvitationDeliveryWorker worker;
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired DriverInvitationDeliveryCipher cipher;
    @Autowired PlatformTransactionManager transactions;
    private Long queue(String key, Instant due, Instant expiry) {
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        var codec = new DriverInvitationTokenCodec(); String token = codec.generate();
        var invitation = new DriverInvitation(); invitation.setDriver(driver); invitation.setCompany(company);
        invitation.setExpiresAt(expiry.truncatedTo(ChronoUnit.MICROS)); invitation.setTokenHash(codec.digest(token));
        invitations.saveAndFlush(invitation);
        var encrypted = cipher.encrypt(token,invitation.getId(),driver.getId(),driver.getPhone(),invitation.getExpiresAt());
        return deliveries.saveAndFlush(new DriverInvitationDelivery(invitation,driver.getPhone(),key,encrypted,due)).getId();
    }
    @Test void acceptedWorkerTransactionSurvivesOuterRollback() {
        Long id = queue("test-key",Instant.now().minusSeconds(1),Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            DriverInvitationDeliveryWorker.Result result = worker.process(id);
            assertThat(result).isEqualTo(DriverInvitationDeliveryWorker.Result.ACCEPTED);
            throw new IllegalStateException("Later sweep failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Later sweep failure");
        assertThat(deliveries.findById(id).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.ACCEPTED);
    }
    @Test void candidateQueryExcludesFutureAndUnknownKeysButIncludesExpiredPayloads() {
        Instant now = Instant.now();
        Long due = queue("test-key",now.minusSeconds(1),now.plusSeconds(3600));
        queue("test-key",now.plusSeconds(600),now.plusSeconds(3600));
        queue("other-key",now.minusSeconds(1),now.plusSeconds(3600));
        Long expired = queue("other-key",now.plusSeconds(600),now.minusSeconds(1));
        var ids = deliveries.findDueIds(DriverInvitationDelivery.State.PENDING,"test-key",now,PageRequest.of(0,10));
        assertThat(ids).containsExactly(due,expired);
    }
    @Test void candidateQueryHonorsBatchLimitAndSkipsAcceptedRows() {
        Instant now = Instant.now();
        Long accepted = queue("test-key",now.minusSeconds(3),now.plusSeconds(3600)); worker.process(accepted);
        Long first = queue("test-key",now.minusSeconds(2),now.plusSeconds(3600));
        queue("test-key",now.minusSeconds(1),now.plusSeconds(3600));
        var ids = deliveries.findDueIds(DriverInvitationDelivery.State.PENDING,"test-key",now,PageRequest.of(0,1));
        assertThat(ids).containsExactly(first);
    }
}
