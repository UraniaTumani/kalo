package com.kalo.auth.service;

import com.kalo.auth.dto.AdminFallbackResetRequest;
import com.kalo.auth.dto.FallbackResetAuditItem;
import com.kalo.auth.dto.ForgotPasswordRequest;
import com.kalo.auth.dto.IssuedResetCodeResponse;
import com.kalo.auth.dto.ResetPasswordRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PasswordResetService {

    /**
     * Mints a code for an account and has it texted to the number on file.
     *
     * Returns nothing, and must behave identically whether the phone belongs to
     * an account or to nobody: the response is the only thing a caller can
     * observe, so anything conditional in it turns this endpoint into a way of
     * asking whether a given number is registered. Suspended accounts, a
     * cooldown not yet elapsed, a daily cap and an exhausted SMS budget all
     * leave by the same silent path for the same reason.
     */
    void requestReset(ForgotPasswordRequest request);

    /**
     * Sets a new password against a code, in one call.
     *
     * Every failure — unknown number, no live code, wrong code, expired code,
     * too many attempts, suspended account — is one indistinguishable refusal.
     */
    void resetPassword(ResetPasswordRequest request);

    /**
     * Issues a code by hand, for an account whose number no longer reaches
     * anybody.
     *
     * The narrow exception to self-service recovery, and the only path that
     * returns a code in a response. Records who allowed it and on what grounds;
     * unlike the endpoints above it does not hide whether the account exists,
     * because the caller is already an authenticated administrator who can see
     * the user list anyway.
     */
    IssuedResetCodeResponse issueFallbackCode(AdminFallbackResetRequest request);

    /** Every code an administrator has ever issued by hand, newest first. */
    Page<FallbackResetAuditItem> fallbackAudit(Pageable pageable);
}
