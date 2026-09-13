package com.kalo.ride.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports whether the ride timeout sweep is still happening.
 *
 * Only one instance sweeps, which is correct but makes the sweep's absence
 * quiet: if that instance stops, nothing fails, no request errors, and rides
 * simply sit in REQUESTED until a passenger gives up. Nobody would find out from
 * a log, because the symptom is the absence of one.
 *
 * So the sweep records when it last got through, and that becomes two things: a
 * gauge to alert on, and a health check that goes DOWN once the gap is wide
 * enough that something must be wrong.
 *
 * Deliberately measures the last *completed* sweep, not the last attempt. An
 * instance that wakes every five seconds and throws every time is not sweeping,
 * and should not look like it is.
 */
@Slf4j
@Component
public class SweepHealth implements HealthIndicator {

    /**
     * Generous next to the five-second period: a single slow sweep, a database
     * blip or a failover should not page anybody. Nothing legitimate leaves a
     * two-minute hole.
     */
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();

    /**
     * True on the instance that is actually sweeping. An instance that never
     * holds the lock is healthy by not sweeping, and must not report DOWN for
     * it — otherwise scaling out would turn every other instance red.
     */
    private final AtomicReference<Boolean> hasEverSwept = new AtomicReference<>(false);

    private final boolean sweepEnabled;

    public SweepHealth(
            MeterRegistry meterRegistry,
            @Value("${app.ride.timeout-sweep.enabled:true}") boolean sweepEnabled
    ) {

        this.sweepEnabled = sweepEnabled;

        /*
         * Seconds since the last completed sweep, or -1 before the first one.
         * A gauge rather than a counter: the question is "how long since", and
         * the answer has to be readable at any moment, not accumulated.
         */
        meterRegistry.gauge(
                "kalo.ride.sweep.seconds.since.success",
                this,
                SweepHealth::secondsSinceLastSuccess
        );
    }

    void recordSuccess() {
        lastSuccess.set(Instant.now());
        hasEverSwept.set(true);
    }

    double secondsSinceLastSuccess() {

        Instant last = lastSuccess.get();

        if (last == null) {
            return -1;
        }

        return Duration.between(last, Instant.now()).toSeconds();
    }

    @Override
    public Health health() {

        /*
         * An instance with the sweep switched off, or one that has never won the
         * lock, is not responsible for it. Reporting on something this instance
         * does not do would make the signal meaningless on every instance but
         * one.
         */
        if (!sweepEnabled || !hasEverSwept.get()) {

            return Health.up()
                    .withDetail("sweeping", false)
                    .withDetail(
                            "reason",
                            sweepEnabled ? "another instance holds the lock" : "disabled here"
                    )
                    .build();
        }

        double seconds = secondsSinceLastSuccess();

        if (seconds > STALE_AFTER.toSeconds()) {

            log.warn(
                    "Ride timeout sweep has not completed for {}s: rides may be stuck in REQUESTED",
                    (long) seconds
            );

            return Health.down()
                    .withDetail("sweeping", true)
                    .withDetail("secondsSinceLastSweep", (long) seconds)
                    .withDetail("staleAfterSeconds", STALE_AFTER.toSeconds())
                    .build();
        }

        return Health.up()
                .withDetail("sweeping", true)
                .withDetail("secondsSinceLastSweep", (long) seconds)
                .build();
    }
}
