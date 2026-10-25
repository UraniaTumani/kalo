package com.kalo.driver.service;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.driver.entity.DriverInvitationDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverInvitationReportSweepTest {
    private final DriverInvitationDeliveryRepository deliveries = mock(DriverInvitationDeliveryRepository.class);
    private final DriverInvitationReportReservation reservations = mock(DriverInvitationReportReservation.class);
    private final DriverInvitationReportCheck checks = mock(DriverInvitationReportCheck.class);
    @Test void queriesBoundedBatchAndCallsOnlyReservedEntries() {
        when(deliveries.findReviewDueIds(eq(DriverInvitationDelivery.State.REVIEW),any(),eq(PageRequest.of(0,10)))).thenReturn(List.of(1L,2L));
        when(reservations.reserve(1L)).thenReturn(false); when(reservations.reserve(2L)).thenReturn(true);
        new DriverInvitationReportSweep(deliveries,reservations,checks,10).processDue();
        verify(checks,never()).check(1L); verify(checks).check(2L);
    }
    @Test void unexpectedFailureStopsBatchWithoutOverridingPriorReservations() {
        when(deliveries.findReviewDueIds(any(),any(),any())).thenReturn(List.of(1L,2L));
        when(reservations.reserve(1L)).thenReturn(true); when(checks.check(1L)).thenThrow(new IllegalStateException("failure"));
        assertThatThrownBy(() -> new DriverInvitationReportSweep(deliveries,reservations,checks,10).processDue()).isInstanceOf(IllegalStateException.class);
        verify(reservations,never()).reserve(2L);
    }
    @Test void rejectsInvalidBatchSizes() {
        for (int size : new int[]{0,-1,51}) assertThatThrownBy(() -> new DriverInvitationReportSweep(deliveries,reservations,checks,size))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
