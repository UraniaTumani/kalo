package com.kalo.auth.service;

import com.kalo.auth.dto.ForgotPasswordRequest;
import com.kalo.auth.dto.IssuedResetCodeResponse;
import com.kalo.auth.dto.PasswordResetQueueItem;
import com.kalo.auth.dto.ResetPasswordRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PasswordResetService {

    /**
     * Records that somebody wants to recover an account.
     *
     * Returns nothing, and must behave identically whether the phone belongs
     * to an account or to nobody: the response is the only thing a caller can
     * observe, so anything conditional in it turns this endpoint into a way of
     * asking whether a given number is registered.
     */
    void requestReset(ForgotPasswordRequest request);

    /** The pending queue, for an administrator to work through. */
    Page<PasswordResetQueueItem> pendingRequests(Pageable pageable);

    /**
     * Mints a code for a request whose owner has been identified.
     *
     * The returned code is not stored and cannot be fetched again.
     */
    IssuedResetCodeResponse issueCode(Long requestId);

    /** Refuses a request outright, for a call that did not check out. */
    void rejectRequest(Long requestId);

    /**
     * Sets a new password against a code.
     *
     * Every failure — unknown number, no live request, wrong code, expired
     * code, too many attempts — is one indistinguishable refusal.
     */
    void resetPassword(ResetPasswordRequest request);
}
