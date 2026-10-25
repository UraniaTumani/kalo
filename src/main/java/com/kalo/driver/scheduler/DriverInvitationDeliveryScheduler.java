package com.kalo.driver.scheduler;

import com.kalo.driver.service.*;
import com.kalo.ride.scheduler.SweepLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** No defaults enable delivery. No provider or encryption-key bean is supplied. */
@Component
@ConditionalOnProperty(name = "app.driver-invitation.scheduler-enabled", havingValue = "true", matchIfMissing = false)
public class DriverInvitationDeliveryScheduler {
    private static final long LOCK_ID = 4_612_004L;
    private static final Logger LOG = LoggerFactory.getLogger(DriverInvitationDeliveryScheduler.class);
    private final DriverInvitationDeliverySweep sweep;
    private final SweepLock lock;
    public DriverInvitationDeliveryScheduler(DriverInvitationDeliverySweep sweep, SweepLock lock,
            ObjectProvider<DriverInvitationDeliveryCipher> ciphers,
            ObjectProvider<DriverInvitationTransport> transports,
            @Value("${app.driver-invitation.worker-enabled:false}") boolean workerEnabled,
            @Value("${app.driver-invitation.delivery-key-id:}") String keyId) {
        if (!workerEnabled || keyId == null || keyId.isBlank() || keyId.length() > 100
                || ciphers.getIfAvailable() == null || transports.getIfAvailable() == null) {
            throw new IllegalStateException("Invitation scheduler requires worker, encryption key, key ID and transport");
        }
        this.sweep = sweep; this.lock = lock;
    }
    @Scheduled(initialDelayString = "${app.driver-invitation.initial-delay:PT1M}",
            fixedDelayString = "${app.driver-invitation.poll-delay:PT30S}")
    public void sweep() {
        try { lock.runExclusively(LOCK_ID, "Driver invitation delivery", sweep::processDue); }
        catch (RuntimeException failed) {
            // Provider exceptions may contain bearer tokens: never log the throwable.
            LOG.warn("Driver invitation delivery sweep failed; pending entries will be checked again");
        }
    }
}
