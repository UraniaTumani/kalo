package com.kalo.ride.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets one instance at a time run a periodic job.
 *
 * The timeout sweep fires on every running instance. Two instances means the
 * same rows are scanned twice every five seconds — which the pessimistic locks
 * in the sweep already make safe, so this is wasted work rather than corruption.
 * The reason it still matters is that "safe but wasteful" quietly caps the
 * deployment at one instance, and that is a thing to discover before a scale-out
 * rather than during one.
 *
 * A PostgreSQL advisory lock rather than a library or a lock table: it needs no
 * dependency, no migration, and nothing to clean up. The lock is tied to the
 * transaction, so it is released on commit, on rollback, and on an instance
 * dying mid-sweep — a lease in a table would instead leave a row that blocks
 * every other instance until it expires, which is the failure mode this is
 * meant to avoid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SweepLock {

    /**
     * Arbitrary but fixed. Advisory locks share one namespace per database, so
     * this number is the name of this particular job; anything else wanting a
     * lock must pick a different one.
     */
    static final long RIDE_TIMEOUT_SWEEP = 4_612_001L;

    /**
     * The driver licence sweep (F36). A different number, as the note above
     * requires: two jobs sharing one advisory lock would mean whichever fired
     * first silently prevented the other from running at all.
     */
    public static final long DRIVER_LICENSE_SWEEP = 4_612_002L;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs the job if this instance can take the lock, and does nothing at all if
     * it cannot.
     *
     * Skipping is the correct outcome, not a failure: another instance is already
     * doing the work, and the job runs again in five seconds regardless.
     *
     * The work runs inside this transaction so the lock is held for its whole
     * duration. {@code processTimedOutRides} is itself transactional and joins
     * this one rather than starting its own.
     */
    @Transactional
    public boolean runExclusively(Runnable work) {
        return take(RIDE_TIMEOUT_SWEEP, "Ride timeout sweep", work);
    }

    /**
     * The same guarantee for a job other than the ride timeout sweep.
     *
     * An overload rather than a changed signature: the original call is the one
     * every existing test exercises, and adding a second job is not a reason to
     * rewrite them.
     */
    @Transactional
    public boolean runExclusively(long lockId, String jobName, Runnable work) {
        return take(lockId, jobName, work);
    }

    /*
     * Private, so it runs inside whichever transaction the proxy opened for the
     * public method that was called. Taking the lock in a method nobody can
     * enter directly is what keeps "the lock is held for the whole job" true.
     */
    private boolean take(long lockId, String jobName, Runnable work) {

        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class,
                lockId
        );

        if (!Boolean.TRUE.equals(acquired)) {

            log.debug("{} skipped: another instance holds the lock", jobName);

            return false;
        }

        work.run();

        return true;
    }
}
