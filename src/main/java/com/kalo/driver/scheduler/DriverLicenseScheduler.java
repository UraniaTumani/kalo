package com.kalo.driver.scheduler;

import com.kalo.driver.service.DriverLicenseSweepService;
import com.kalo.ride.scheduler.SweepLock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decides when the licence sweep runs; the service decides what it does.
 *
 * Same split as RideTimeoutScheduler, for the same reason: a test can run one
 * sweep at a moment it chooses instead of racing a timer against its own
 * fixtures.
 *
 * Hourly rather than every few seconds. A licence expires on a date, so the
 * state this watches changes once a day at midnight in Europe/Tirane; polling
 * it faster would only add queries. An hour bounds the worst case at a driver
 * showing as ONLINE for up to an hour into the day their licence lapsed — which
 * costs nothing, because search and ride acceptance have already stopped
 * offering them by then. This sweep corrects the display, it does not police
 * eligibility.
 *
 * The timer fires on every instance and the work is taken through SweepLock
 * under its own advisory lock, so scaling out does not multiply the scan.
 *
 * Disabled in the test suite, like the ride timeout sweep and the rate limiter:
 * a sweep firing mid-test would take fixture drivers offline underneath the test
 * that had just put them online.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.driver.license-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DriverLicenseScheduler {

    private final DriverLicenseSweepService driverLicenseSweepService;
    private final SweepLock sweepLock;

    /**
     * An initial delay so startup is not competing with the first sweep, and a
     * fixed delay rather than a fixed rate so a slow sweep cannot overlap itself.
     */
    @Scheduled(
            initialDelayString = "PT1M",
            fixedDelayString = "PT1H"
    )
    public void sweep() {

        sweepLock.runExclusively(
                SweepLock.DRIVER_LICENSE_SWEEP,
                "Driver license sweep",
                driverLicenseSweepService::takeExpiredDriversOffline
        );
    }
}
