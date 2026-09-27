package com.kalo.auth.service;

import com.kalo.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts a recovery code on its way, off the request thread and after the commit.
 *
 * Both of those are deliberate, and the first is a security property rather
 * than a performance one.
 *
 * {@code POST /password/forgot} answers at a fixed 250ms so that a registered
 * number and an unknown one take the same time to refuse — see
 * {@code ConstantTime}. A provider's HTTP call takes a few hundred milliseconds
 * when there is something to send and exactly nothing when there is not, so
 * sending inline would overrun the floor for registered numbers only. The
 * endpoint would then be a more reliable oracle than it was before the floor
 * existed, because the signal would be hundreds of milliseconds rather than the
 * two or three the database queries cost.
 *
 * AFTER_COMMIT is the second half. A message about a code that was never
 * persisted is worse than no message: the recipient would have a code the
 * server has no record of, and would be told it was wrong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetSmsDispatcher {

    /**
     * Unaccented on purpose, and short.
     *
     * "ë" is outside GSM-7, so one of them turns the whole message into UCS-2
     * and cuts the segment from 160 characters to 70 — which for a message this
     * near the boundary means paying twice. Albanian written without diacritics
     * is ordinary in a text message, so the cost buys nothing.
     *
     * No account name, no link, and nothing about who is asking. A recovery
     * message is delivered to a number that may not be the account holder's,
     * and the less it says about the account the better.
     */
    private static final String TEMPLATE =
            "MR TAXI: kodi %s. Skadon pas 5 min. Mos e ndani me asnjeri.";

    private final SmsSender smsSender;

    @Async("smsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCodeIssued(PasswordResetCodeIssued event) {

        try {
            smsSender.send(event.phone(), TEMPLATE.formatted(event.rawCode()));

            /*
             * userId and expiry, never the code and never the number in full —
             * the log is the one place a code could survive long enough to be
             * useful to somebody reading it later.
             */
            log.info(
                    "Recovery code dispatched: userId={} expiresAt={}",
                    event.userId(),
                    event.expiresAt()
            );
        } catch (RuntimeException failed) {
            /*
             * Swallowed, because there is nobody left to tell: the response
             * went out before this thread started, and it deliberately said
             * nothing about whether a message was coming. Throwing here would
             * put a stack trace on a background thread and change nothing the
             * caller can see.
             *
             * The person is not stranded — the code is stored and stays valid
             * for its five minutes, so a retry from the page sends another, and
             * the admin fallback exists for the case where nothing arrives.
             */
            log.error(
                    "Recovery code could not be dispatched: userId={}",
                    event.userId(),
                    failed
            );
        }
    }
}
