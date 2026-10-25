package com.kalo.driver.service;
import com.kalo.driver.entity.*;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverInvitationReportScheduleIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationReportReservation reservations;
    @Autowired DriverInvitationReconciliation reconciliation;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired DriverInvitationRepository invitations;
    @Autowired PlatformTransactionManager transactions;
    private Long review() {
        var company=fixtures.approvedCompany(); var driver=fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        var invitation=new DriverInvitation(); invitation.setCompany(company); invitation.setDriver(driver);
        var codec=new DriverInvitationTokenCodec(); invitation.setTokenHash(codec.digest(codec.generate()));
        invitation.setExpiresAt(Instant.now().plusSeconds(3600)); invitations.saveAndFlush(invitation);
        Instant now=Instant.now(); var row=new DriverInvitationDelivery(invitation,driver.getPhone(),"test-key","encrypted",now);
        row.recordProviderMessageId("00000000-0000-0000-0000-000000000001"); row.requireReview(now);
        return deliveries.saveAndFlush(row).getId();
    }
    @SuppressWarnings("unchecked") private DriverInvitationReportSweep sweep(DriverInvitationReportReader reader) {
        ObjectProvider<DriverInvitationReportReader> provider=mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(reader);
        var check=new DriverInvitationReportCheck(deliveries,reconciliation,provider,true);
        return new DriverInvitationReportSweep(deliveries,reservations,check,10);
    }
    @Test void repeatedSweepDoesNotRepeatLookupBeforePersistedBackoff() {
        Long id=review(); var reader=mock(DriverInvitationReportReader.class);
        when(reader.lookup(anyString(),anyString())).thenReturn(DriverInvitationReportReader.Evidence.UNKNOWN);
        var sweep=sweep(reader); sweep.processDue(); sweep.processDue();
        verify(reader).lookup(anyString(),anyString());
        var row=deliveries.findById(id).orElseThrow(); assertThat(row.getReportChecks()).isEqualTo(1);
        assertThat(row.getNextReportCheckAt()).isAfter(Instant.now());
        assertThat(row.getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
    }
    @Test void oldEntryStopsAutomaticallyWithoutCallingProvider() {
        Long id=review(); jdbcTemplate.update("UPDATE driver_invitation_deliveries SET created_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(48*3600L)),id);
        var reader=mock(DriverInvitationReportReader.class); sweep(reader).processDue();
        verifyNoInteractions(reader); var row=deliveries.findById(id).orElseThrow();
        assertThat(row.getReportStopReason()).isEqualTo(DriverInvitationDelivery.ReportStopReason.AGE_LIMIT);
        assertThat(row.getReportChecks()).isZero(); assertThat(row.getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
    }
    @Test void reservationSurvivesCallerFailure() {
        Long id=review();
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            assertThat(reservations.reserve(id)).isTrue(); throw new IllegalStateException("Lookup process stopped");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Lookup process stopped");
        assertThat(deliveries.findById(id).orElseThrow().getReportChecks()).isEqualTo(1);
        assertThat(reservations.reserve(id)).isFalse();
    }
    @Test void competingReservationsConsumeOnlyOneLookup() throws Exception {
        Long id=review(); var executor=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        Callable<Boolean> attempt=()->{ready.countDown(); if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("No start");return reservations.reserve(id);};
        try {
            var first=executor.submit(attempt); var second=executor.submit(attempt);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Boolean> outcomes=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true,false);
            assertThat(deliveries.findById(id).orElseThrow().getReportChecks()).isEqualTo(1);
        } finally {start.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
