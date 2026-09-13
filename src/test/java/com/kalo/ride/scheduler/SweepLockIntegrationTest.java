package com.kalo.ride.scheduler;

import com.kalo.ride.service.RideTimeoutService;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keeps the timeout sweep to one instance at a time.
 *
 * The timer fires on every instance that is running, so without this the same
 * rows are scanned once per instance every five seconds. The pessimistic locks
 * inside the sweep already make that safe, which is exactly why it would go
 * unnoticed: nothing breaks, the deployment is just quietly capped at one
 * instance.
 *
 * A second database connection stands in for a second instance here, which is
 * what it looks like to PostgreSQL either way.
 */
@DisplayName("The sweep lock")
class SweepLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    SweepLock sweepLock;

    @Autowired
    RideTimeoutService rideTimeoutService;

    @Autowired
    DataSource dataSource;

    @Autowired
    SweepHealth sweepHealth;

    /** Held open while a test pretends to be another instance. */
    private Connection otherInstance;

    @AfterEach
    void releaseOtherInstance() throws Exception {

        if (otherInstance != null) {
            otherInstance.close();
            otherInstance = null;
        }
    }

    @Test
    @DisplayName("the work runs when nothing else holds the lock")
    void runsWhenLockIsFree() {

        AtomicInteger runs = new AtomicInteger();

        boolean ran = sweepLock.runExclusively(runs::incrementAndGet);

        assertThat(ran).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("the work is skipped while another instance holds the lock")
    void skipsWhileAnotherInstanceHoldsTheLock() throws Exception {

        holdLockOnAnotherConnection();

        AtomicInteger runs = new AtomicInteger();

        boolean ran = sweepLock.runExclusively(runs::incrementAndGet);

        // Skipping is the whole point: the other instance is doing it, and this
        // one tries again in five seconds.
        assertThat(ran).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("the lock is free again once the work finishes")
    void lockIsReleasedAfterTheWork() {

        assertThat(sweepLock.runExclusively(() -> { })).isTrue();

        // Tied to the transaction, so committing hands it back. A sweep that had
        // to wait for the next tick to release would halve the effective rate.
        assertThat(sweepLock.runExclusively(() -> { })).isTrue();
    }

    @Test
    @DisplayName("work that throws still gives the lock back")
    void lockIsReleasedWhenTheWorkFails() {

        assertThatThrownBy(() ->
                sweepLock.runExclusively(() -> {
                    throw new IllegalStateException("sweep blew up");
                })
        ).isInstanceOf(IllegalStateException.class);

        /*
         * The rollback releases it. A lease row in a table would instead sit
         * there blocking every other instance until it expired, which is the
         * failure this approach avoids.
         */
        assertThat(sweepLock.runExclusively(() -> { })).isTrue();
    }

    @Test
    @DisplayName("a held lock stops the scheduler from sweeping at all")
    void schedulerDoesNotSweepWhileLocked() throws Exception {

        holdLockOnAnotherConnection();

        AtomicInteger sweeps = new AtomicInteger();

        /*
         * Built by hand rather than autowired: the scheduler bean is switched off
         * in the test suite, and switching it on would start the five-second
         * timer this test is trying to reason about.
         */
        RideTimeoutScheduler scheduler =
                new RideTimeoutScheduler(sweeps::incrementAndGet, sweepLock, sweepHealth);

        scheduler.sweep();

        assertThat(sweeps).hasValue(0);
    }

    @Test
    @DisplayName("the scheduler sweeps when the lock is free")
    void schedulerSweepsWhenFree() {

        AtomicInteger sweeps = new AtomicInteger();

        RideTimeoutScheduler scheduler =
                new RideTimeoutScheduler(sweeps::incrementAndGet, sweepLock, sweepHealth);

        scheduler.sweep();

        assertThat(sweeps).hasValue(1);
    }

    @Test
    @DisplayName("the real sweep still runs through the lock")
    void realSweepRunsThroughTheLock() {

        // Guards the wiring rather than the logic: RideTimeoutIntegrationTest
        // covers what a sweep actually does.
        assertThat(sweepLock.runExclusively(rideTimeoutService::processTimedOutRides)).isTrue();
    }

    /**
     * Takes the same advisory lock from a separate connection and keeps it. A
     * session-level lock and the transaction-level one the sweep uses share a
     * namespace, so this contends exactly as a second instance would.
     */
    private void holdLockOnAnotherConnection() throws Exception {

        otherInstance = dataSource.getConnection();

        try (Statement statement = otherInstance.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + SweepLock.RIDE_TIMEOUT_SWEEP + ")");
        }
    }
}
