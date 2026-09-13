package com.kalo.ride.scheduler;

import com.kalo.ride.service.RideTimeoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decides when the timeout sweep runs; the service decides what it does.
 *
 * Keeping the trigger out of the service is what makes the sweep testable: a
 * test can run one sweep at a known moment instead of racing a timer that fires
 * every five seconds against its own fixtures.
 *
 * The timer fires on every instance, so the work is taken through SweepLock and
 * only one instance actually does it. Scaling out therefore adds capacity to
 * serve requests without multiplying the background scan.
 *
 * Disabled in the test suite, the same way the rate limiter is.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.ride.timeout-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RideTimeoutScheduler {

    private final RideTimeoutService rideTimeoutService;
    private final SweepLock sweepLock;
    private final SweepHealth sweepHealth;

    @Scheduled(fixedDelay = 5000)
    public void sweep() {

        boolean swept = sweepLock.runExclusively(rideTimeoutService::processTimedOutRides);

        /*
         * Recorded only when this instance did the work and it returned. A
         * sweep that threw leaves the clock running, which is the point: the
         * health check is asking whether rides are being timed out, not
         * whether a timer is ticking.
         */
        if (swept) {
            sweepHealth.recordSuccess();
        }
    }
}
