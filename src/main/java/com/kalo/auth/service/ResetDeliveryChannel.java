package com.kalo.auth.service;

import com.kalo.auth.entity.PasswordResetRequest;
import com.kalo.user.entity.User;

/**
 * How a recovery reaches the person who asked for it.
 *
 * Kept separate from {@link PasswordResetService} because the two answer
 * different questions and change for different reasons. The service decides
 * whether a reset is allowed, how long a code lives and how many guesses it
 * survives — none of which depends on whether the code travels by telephone,
 * SMS or email. Buying an SMS account should be an afternoon's work here and
 * no work at all there.
 *
 * Today there is one implementation, {@link AdminQueueDeliveryChannel}, and it
 * delivers nothing: an administrator reads the code aloud. That is not a
 * placeholder standing in for a real provider — it is the only honest option
 * while KALO has no provider and an unverified phone number, and pretending
 * otherwise would mean telling people to check a message that was never sent.
 */
public interface ResetDeliveryChannel {

    /**
     * A recovery has been queued. No code exists yet, deliberately: nothing is
     * minted until somebody has been identified.
     */
    void onRequested(User user, PasswordResetRequest request);

    /**
     * A code has been minted for an identified account holder.
     *
     * An implementation that can actually send something delivers it here and
     * the raw code never reaches an API response. The admin-queue
     * implementation does nothing, because the administrator on the telephone
     * is the delivery.
     */
    void onCodeIssued(User user, PasswordResetRequest request, String rawCode);
}
