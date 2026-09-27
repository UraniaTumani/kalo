package com.kalo.common.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Makes an endpoint take the same time to answer whatever it did.
 *
 * Some answers are supposed to be indistinguishable. Password recovery returns
 * the same body for a registered number and an unknown one, deliberately, so
 * that the endpoint cannot be asked whether somebody has an account. The body
 * is only half of what a caller can see, though: an unknown number costs one
 * SELECT, and a registered one costs a lookup, a count, an update and an
 * insert. That difference is small but it is consistent, and consistent is all
 * an attacker needs — sample the same number often enough and the mean
 * separates cleanly from the noise.
 *
 * So the work is allowed to take as long as it takes, and the answer is held
 * back until a fixed point. What the caller measures is then the floor, not
 * the work.
 *
 * The honest limit of this: if the real work ever exceeds the floor, the part
 * above it is visible again. That is logged rather than hidden, because an
 * overrun is precisely the moment the guarantee stops holding.
 */
@Slf4j
public final class ConstantTime {

    private ConstantTime() {
    }

    /**
     * Sleeps until {@code floor} has passed since {@code startedAtNanos}.
     *
     * Call it from a finally block, so a request that fails takes as long as
     * one that succeeds — a refusal that comes back faster is the same leak in
     * a different coat.
     *
     * Deliberately not called inside a transaction: waiting there would hold a
     * pooled connection open for the whole floor, and the connection pool is a
     * far scarcer resource than a request thread.
     */
    public static void holdUntil(long startedAtNanos, Duration floor) {

        long elapsedNanos = System.nanoTime() - startedAtNanos;
        long remainingNanos = floor.toNanos() - elapsedNanos;

        if (remainingNanos <= 0) {
            log.warn(
                    "Constant-time floor overrun: work took {}ms against a {}ms floor, "
                            + "so the difference above the floor is observable",
                    elapsedNanos / 1_000_000,
                    floor.toMillis()
            );
            return;
        }

        try {
            Thread.sleep(remainingNanos / 1_000_000, (int) (remainingNanos % 1_000_000));
        } catch (InterruptedException interrupted) {
            /*
             * Restore the flag and return. Swallowing the interrupt would keep
             * a shutting-down container waiting on a delay whose only purpose
             * is to be indistinguishable.
             */
            Thread.currentThread().interrupt();
        }
    }
}
