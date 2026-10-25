package com.kalo.driver.service;
import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverInvitationReportCheckTest {
    private final DriverInvitationDeliveryRepository deliveries = mock(DriverInvitationDeliveryRepository.class);
    private final DriverInvitationReconciliation reconciliation = mock(DriverInvitationReconciliation.class);
    private final DriverInvitationReportReader reader = mock(DriverInvitationReportReader.class);
    @SuppressWarnings("unchecked") private DriverInvitationReportCheck check(boolean enabled,boolean configured) {
        ObjectProvider<DriverInvitationReportReader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configured ? reader : null);
        return new DriverInvitationReportCheck(deliveries,reconciliation,provider,enabled);
    }
    private void review() {
        var row = mock(DriverInvitationDelivery.class);
        when(row.getState()).thenReturn(DriverInvitationDelivery.State.REVIEW);
        when(row.getProviderMessageId()).thenReturn("message-id"); when(row.getDestination()).thenReturn("+355690000001");
        when(deliveries.findById(1L)).thenReturn(Optional.of(row));
    }
    @Test void disabledOrMissingReaderDoesNoWork() {
        assertThat(check(false,true).check(1L)).isEqualTo(DriverInvitationReportCheck.Result.DISABLED);
        assertThat(check(true,false).check(1L)).isEqualTo(DriverInvitationReportCheck.Result.DISABLED);
        verifyNoInteractions(deliveries,reader,reconciliation);
    }
    @Test void confirmedEvidenceUsesStoredIdentityAndDestination() {
        review(); when(reader.lookup("message-id","+355690000001")).thenReturn(DriverInvitationReportReader.Evidence.ACCEPTED);
        assertThat(check(true,true).check(1L)).isEqualTo(DriverInvitationReportCheck.Result.RESOLVED);
        verify(reconciliation).applyVerifiedEvidence(1L,"message-id",DriverInvitationReconciliation.Evidence.PROVIDER_ACCEPTED);
    }
    @Test void unknownNullOrThrownOutcomeNeverResolvesReview() {
        review(); when(reader.lookup(anyString(),anyString())).thenReturn(DriverInvitationReportReader.Evidence.UNKNOWN).thenReturn(null)
                .thenThrow(new IllegalStateException("secret"));
        for (int n=0;n<3;n++) assertThat(check(true,true).check(1L)).isEqualTo(DriverInvitationReportCheck.Result.UNRESOLVED);
        verifyNoInteractions(reconciliation);
    }
    @Test void nonexistentOrTerminalEntryDoesNotQueryProvider() {
        assertThat(check(true,true).check(1L)).isEqualTo(DriverInvitationReportCheck.Result.SKIPPED);
        var row = mock(DriverInvitationDelivery.class); when(row.getState()).thenReturn(DriverInvitationDelivery.State.ACCEPTED);
        when(deliveries.findById(1L)).thenReturn(Optional.of(row));
        assertThat(check(true,true).check(1L)).isEqualTo(DriverInvitationReportCheck.Result.SKIPPED);
        verifyNoInteractions(reader,reconciliation);
    }
    @Test void explicitRejectionAppliesFailureEvidence() {
        review(); when(reader.lookup(anyString(),anyString())).thenReturn(DriverInvitationReportReader.Evidence.REJECTED);
        assertThat(check(true,true).check(1L)).isEqualTo(DriverInvitationReportCheck.Result.RESOLVED);
        verify(reconciliation).applyVerifiedEvidence(1L,"message-id",DriverInvitationReconciliation.Evidence.PROVIDER_REJECTED);
    }
}
