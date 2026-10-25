package com.kalo.driver.scheduler;
import com.kalo.driver.service.DriverInvitationReportReader;
import com.kalo.driver.service.DriverInvitationReportSweep;
import com.kalo.ride.scheduler.SweepLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DriverInvitationReportSchedulerTest {
    private final DriverInvitationReportSweep sweep = mock(DriverInvitationReportSweep.class);
    private final SweepLock lock = mock(SweepLock.class);
    @SuppressWarnings("unchecked") private DriverInvitationReportScheduler scheduler() {
        ObjectProvider<DriverInvitationReportReader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(DriverInvitationReportReader.class));
        return new DriverInvitationReportScheduler(sweep,lock,provider,true);
    }
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(DriverInvitationReportScheduler.class)
                .withBean(DriverInvitationReportSweep.class,()->sweep).withBean(SweepLock.class,()->lock);
    }
    @Test void absentByDefault() { context().run(ctx->assertThat(ctx).doesNotHaveBean(DriverInvitationReportScheduler.class)); }
    @Test void explicitEnablementWithoutReaderFailsStartup() {
        context().withPropertyValues("app.driver-invitation.report-scheduler-enabled=true","app.driver-invitation.report-check-enabled=true")
                .run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void completeFakeConfigurationCreatesSchedulerWithoutPolling() {
        context().withPropertyValues("app.driver-invitation.report-scheduler-enabled=true","app.driver-invitation.report-check-enabled=true")
                .withBean(DriverInvitationReportReader.class,()->mock(DriverInvitationReportReader.class))
                .run(ctx->assertThat(ctx).hasSingleBean(DriverInvitationReportScheduler.class)); verifyNoInteractions(sweep);
    }
    @Test void usesDedicatedAdvisoryLock() {
        doAnswer(call->{ ((Runnable)call.getArgument(2)).run(); return true; }).when(lock)
                .runExclusively(eq(4612005L),eq("Driver invitation report reconciliation"),any(Runnable.class));
        scheduler().sweep(); verify(sweep).processDue();
    }
    @Test void heldLockSkipsPolling() { scheduler().sweep(); verifyNoInteractions(sweep); }
    @Test void failureIsSanitizedByScheduler() {
        when(lock.runExclusively(anyLong(),anyString(),any(Runnable.class))).thenThrow(new IllegalStateException("secret"));
        assertThatCode(()->scheduler().sweep()).doesNotThrowAnyException();
    }
}
