package com.kalo.driver.service;
import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverInvitationDeliverySweepTest {
    private final DriverInvitationDeliveryRepository repository = mock(DriverInvitationDeliveryRepository.class);
    private final DriverInvitationDeliveryWorker worker = mock(DriverInvitationDeliveryWorker.class);
    @Test void usesBoundedQueryAndCallsWorkerInCandidateOrder() {
        when(repository.findDueIds(eq(DriverInvitationDelivery.State.PENDING),eq("test-key"),any(),eq(PageRequest.of(0,10))))
                .thenReturn(List.of(2L,3L));
        new DriverInvitationDeliverySweep(repository,worker,10,"test-key").processDue();
        var order = inOrder(worker); order.verify(worker).process(2L); order.verify(worker).process(3L);
    }
    @Test void emptyBatchDoesNoWork() {
        new DriverInvitationDeliverySweep(repository,worker,10,"test-key").processDue(); verifyNoInteractions(worker);
    }
    @Test void unexpectedFailureStopsBatchAndPropagatesToSanitizedSchedulerHandler() {
        when(repository.findDueIds(any(),anyString(),any(),any())).thenReturn(List.of(2L,3L));
        when(worker.process(2L)).thenThrow(new IllegalStateException("failure"));
        assertThatThrownBy(() -> new DriverInvitationDeliverySweep(repository,worker,10,"test-key").processDue())
                .isInstanceOf(IllegalStateException.class);
        verify(worker,never()).process(3L);
    }
    @Test void rejectsUnboundedBatchSize() {
        for (int size : new int[]{0,-1,51}) {
            assertThatThrownBy(() -> new DriverInvitationDeliverySweep(repository,worker,size,"test-key"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
