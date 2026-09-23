package com.kalo.auth.service;

import com.kalo.auth.entity.PasswordResetRequest;
import com.kalo.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Delivery by telephone call, which is to say: no delivery at all.
 *
 * This implementation sends nothing and is not trying to. KALO has no SMS or
 * mail provider, and the phone number on an account has never been verified,
 * so there is no channel that could carry a code to the account holder rather
 * than to whoever typed the number. The verification is therefore an
 * administrator ringing the number already on file and satisfying themselves
 * they are talking to the right person, and the code goes down that line.
 *
 * What this class does is leave a trail. Both hooks log, so a run of requests
 * against one account is visible, and every issued code names the
 * administrator who took responsibility for it.
 *
 * Neither method ever logs the code. A reset code in a log file is a reset
 * code in every log aggregator, terminal scrollback and support ticket it is
 * ever pasted into.
 */
@Slf4j
@Component
public class AdminQueueDeliveryChannel implements ResetDeliveryChannel {

    @Override
    public void onRequested(User user, PasswordResetRequest request) {
        log.info(
                "Password reset awaiting identity check: requestId={} userId={} role={}",
                request.getId(),
                user.getId(),
                user.getRole()
        );
    }

    @Override
    public void onCodeIssued(
            User user,
            PasswordResetRequest request,
            String rawCode
    ) {
        log.info(
                "Password reset code handed to an administrator to read out: "
                        + "requestId={} userId={} expiresAt={}",
                request.getId(),
                user.getId(),
                request.getExpiresAt()
        );
    }
}
