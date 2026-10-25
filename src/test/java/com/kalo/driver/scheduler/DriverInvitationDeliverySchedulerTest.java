package com.kalo.driver.scheduler;
import com.kalo.driver.service.*;
import com.kalo.ride.scheduler.SweepLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriverInvitationDeliverySchedulerTest {
    private final DriverInvitationDeliverySweep sweep = mock(DriverInvitationDeliverySweep.class);
    private final SweepLock lock = mock(SweepLock.class);
    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance); return provider;
    }
    private DriverInvitationDeliveryScheduler scheduler() {
        return new DriverInvitationDeliveryScheduler(sweep, lock,
                provider(new DriverInvitationDeliveryCipher(new byte[32])),
                provider(mock(DriverInvitationTransport.class)), true, "test-key");
    }
    @Test void usesDedicatedAdvisoryLockAndDelegatesSweep() {
        doAnswer(call -> { ((Runnable)call.getArgument(2)).run(); return true; })
                .when(lock).runExclusively(eq(4612004L), eq("Driver invitation delivery"), any(Runnable.class));
        scheduler().sweep(); verify(sweep).processDue();
    }
    @Test void heldAdvisoryLockDoesNotProcessEntries() {
        scheduler().sweep(); verifyNoInteractions(sweep);
    }
    @Test void schedulerDoesNotPropagateSensitiveFailure() {
        when(lock.runExclusively(anyLong(),anyString(),any(Runnable.class)))
                .thenThrow(new IllegalStateException("sensitive-provider-body"));
        assertThatCode(() -> scheduler().sweep()).doesNotThrowAnyException();
    }
    @Test void rejectsIncompleteRuntimePrerequisites() {
        var cipher = provider(new DriverInvitationDeliveryCipher(new byte[32]));
        var transport = provider(mock(DriverInvitationTransport.class));
        assertThatThrownBy(() -> new DriverInvitationDeliveryScheduler(sweep,lock,cipher,transport,false,"key"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DriverInvitationDeliveryScheduler(sweep,lock,cipher,transport,true,""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DriverInvitationDeliveryScheduler(sweep,lock,provider(null),transport,true,"key"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DriverInvitationDeliveryScheduler(sweep,lock,cipher,provider(null),true,"key"))
                .isInstanceOf(IllegalStateException.class);
    }
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(DriverInvitationDeliveryScheduler.class)
                .withBean(DriverInvitationDeliverySweep.class, () -> sweep).withBean(SweepLock.class, () -> lock);
    }
    @Test void schedulerBeanIsAbsentByDefault() {
        context().run(ctx -> assertThat(ctx).doesNotHaveBean(DriverInvitationDeliveryScheduler.class));
    }
    @Test void explicitEnablementWithoutDependenciesFailsStartup() {
        context().withPropertyValues("app.driver-invitation.scheduler-enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void completeTestConfigurationCreatesSchedulerWithoutSending() {
        context().withPropertyValues("app.driver-invitation.scheduler-enabled=true",
                        "app.driver-invitation.worker-enabled=true", "app.driver-invitation.delivery-key-id=test-key")
                .withBean(DriverInvitationDeliveryCipher.class, () -> new DriverInvitationDeliveryCipher(new byte[32]))
                .withBean(DriverInvitationTransport.class, () -> mock(DriverInvitationTransport.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(DriverInvitationDeliveryScheduler.class));
        verifyNoInteractions(sweep);
    }
}
