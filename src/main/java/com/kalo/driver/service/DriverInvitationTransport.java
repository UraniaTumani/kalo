package com.kalo.driver.service;

/**
 * Provider contract; the Infobip implementation is unwired and has no Spring bean.
 * Must use bounded network timeouts and never log tokens, request/response bodies
 * or provider exceptions. Retry with the SAME idempotency key. Return ACCEPTED
 * only after parsing per-message provider acceptance, not merely HTTP 2xx.
 * Ambiguous responses require REVIEW_REQUIRED; only known-safe rejection is retryable.
 * No exactly-once guarantee is implied: a crash after provider acceptance but
 * before DB commit can repeat the attempt; provider deduplication/reconciliation must be verified before live enablement.
 */
public interface DriverInvitationTransport {
    enum Outcome { ACCEPTED, RETRYABLE_FAILURE, PERMANENT_FAILURE, REVIEW_REQUIRED }
    Outcome send(String destination, String token, String idempotencyKey);
}
