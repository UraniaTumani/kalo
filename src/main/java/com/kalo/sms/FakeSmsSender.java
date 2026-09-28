package com.kalo.sms;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The only implementation there is: it does not send anything.
 *
 * This exists so the rest of the flow can be built, tested and demonstrated
 * before anybody signs a contract with a provider. It is honest about that in
 * two ways, both of which matter.
 *
 * It refuses to be quiet outside development. Every send logs a WARN naming the
 * fact that no message left the building, because the failure mode this guards
 * against is a deployment that looks configured, tells people to check their
 * phone, and silently drops every code. The previous design avoided that by
 * never claiming to send anything at all; this one avoids it by complaining.
 *
 * And it never logs the message body unless a developer has asked for it on
 * their own machine. The body contains a live one-time code, and a code in a
 * log file is a code in every aggregator, terminal scrollback and pasted
 * support ticket it reaches afterwards. {@code app.sms.log-message} is false by
 * default and the dev profile is the only thing that turns it on.
 *
 * Tests read the code from {@link #lastMessageTo(String)} rather than from a
 * log, which is also why the buffer exists.
 */
@Slf4j
public class FakeSmsSender implements SmsSender {

    /**
     * Enough for a test to find what it just sent, and small enough that a long
     * running dev session cannot grow it without bound. Bounded rather than
     * cleared on read, so two assertions about the same message both work.
     */
    private static final int BUFFER_SIZE = 50;

    private final boolean logMessage;
    private final boolean development;

    private final Deque<Sent> sent = new ArrayDeque<>();

    public FakeSmsSender(boolean logMessage, boolean development) {
        this.logMessage = logMessage;
        this.development = development;
    }

    @Override
    public void send(String phone, String message) {

        synchronized (sent) {
            sent.addFirst(new Sent(phone, message, Instant.now()));

            while (sent.size() > BUFFER_SIZE) {
                sent.removeLast();
            }
        }

        if (logMessage) {
            /*
             * Development only, and the one place a plaintext code is allowed
             * to appear. A developer with no provider account has no other way
             * to complete the flow on their own machine, and the alternative —
             * an endpoint that hands back the code — would be a hole that
             * could reach production.
             */
            log.warn("SMS not sent (no provider). To {}: {}", phone, message);
            return;
        }

        if (development) {
            log.info("SMS not sent (no provider): to={} length={}", phone, message.length());
            return;
        }

        /*
         * WARN, every time, because this is a real deployment with no way to
         * reach anybody. Somebody is being told to check their phone for a
         * message that does not exist, and the only way out is the admin
         * fallback. That should be noisy in the logs until a provider is
         * configured.
         */
        log.warn(
                "SMS NOT SENT: no provider is configured (app.sms.provider=log), so the "
                        + "recovery code for {} was not delivered. Recovery for this account "
                        + "requires the admin fallback until a provider is configured.",
                phone
        );
    }

    /**
     * Whether this instance will write the message body to the log.
     *
     * Exposed so SmsConfigTest can assert that the flag is refused outside
     * development, which is the property that keeps live codes out of a real
     * deployment log rather than merely discouraged.
     */
    public boolean revealsMessage() {
        return logMessage;
    }

    /** The most recent message sent to this number, for tests and for dev. */
    public Optional<String> lastMessageTo(String phone) {

        synchronized (sent) {
            return sent.stream()
                    .filter(entry -> entry.phone().equals(phone))
                    .map(Sent::message)
                    .findFirst();
        }
    }

    /** How many messages have been sent to this number, newest-bounded. */
    public List<Sent> messagesTo(String phone) {

        synchronized (sent) {
            return sent.stream().filter(entry -> entry.phone().equals(phone)).toList();
        }
    }

    /** Drops the buffer, so one test's messages are not visible to the next. */
    public void clear() {

        synchronized (sent) {
            sent.clear();
        }
    }

    public record Sent(String phone, String message, Instant at) {
    }
}
