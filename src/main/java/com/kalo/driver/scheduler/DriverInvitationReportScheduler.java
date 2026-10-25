package com.kalo.driver.scheduler;
import com.kalo.driver.service.DriverInvitationReportReader;
import com.kalo.driver.service.DriverInvitationReportSweep;
import com.kalo.ride.scheduler.SweepLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Component
@ConditionalOnProperty(name="app.driver-invitation.report-scheduler-enabled",havingValue="true",matchIfMissing=false)
public class DriverInvitationReportScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(DriverInvitationReportScheduler.class);
    private final DriverInvitationReportSweep sweep;
    private final SweepLock lock;
    public DriverInvitationReportScheduler(DriverInvitationReportSweep sweep,SweepLock lock,
            ObjectProvider<DriverInvitationReportReader> readers,
            @Value("${app.driver-invitation.report-check-enabled:false}") boolean enabled) {
        if (!enabled || readers.getIfAvailable() == null) throw new IllegalStateException("Report scheduler requires enabled checker and configured reader");
        this.sweep = sweep; this.lock = lock;
    }
    @Scheduled(initialDelayString="${app.driver-invitation.report-initial-delay:PT2M}",
            fixedDelayString="${app.driver-invitation.report-poll-delay:PT1M}")
    public void sweep() {
        try { lock.runExclusively(4612005L,"Driver invitation report reconciliation",sweep::processDue); }
        catch (RuntimeException failure) { LOG.warn("Invitation report reconciliation failed; reserved entries remain paced for review"); }
    }
}
