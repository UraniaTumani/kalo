# Invitation delivery reconciliation — pending local verification

This step supersedes the earlier SMS adapter outcome mapping. Ambiguous provider
results are no longer blindly resent. Timeouts, HTTP 408/5xx, malformed/uncorrelated
responses, unknown outcomes and duplicate-message rejection enter REVIEW. HTTP 429
and explicit provider system rejection (group 5/id 21) remain bounded retries.
The existing worker still serializes competing sends and uses a stable message ID.

Migration 030 adds provider_message_id and permits REVIEW. The worker records the
correlation ID before making the send call. REVIEW increments attempts once, clears
the encrypted token payload and is excluded from send scans. No acceptance or
handset-delivery claim is inferred from a timeout or duplicate ID.
The correlation ID is reconstructible from invitation ID, token digest and queued
destination even if a crash rolls back the transaction surrounding a send.
A crash can still repeat a provider request before its response is recorded;
this change does not claim exactly-once external side effects.

An internal applyVerifiedEvidence method locks the queue entry, checks exact
message-ID correlation and resolves REVIEW to ACCEPTED or FAILED. Replayed
identical evidence is idempotent; mismatched or conflicting evidence is rejected.
The method is not exposed through an API. Its future caller must independently
authenticate the provider report and validate destination/status and provenance.
No real report polling, webhook, operator UI or automatic reconciliation is enabled.
A missing report or duplicate-ID error alone cannot count as verified acceptance.

Migration rollback is blocked while REVIEW rows exist, because the old state
constraint cannot represent them. Reconcile/review them before a planned rollback;
no automatic rewriting or deletion of these records is supplied.

Ten new tests cover durable review, secret cleanup, no resend, message identity,
correlated evidence and conflicting/replayed results. Existing adapter/worker tests
are updated for the new outcome mapping. Expected suite: 582 tests, 36 changesets,
subject to successful local verification. This is not production certification.
Sending and activation remain disabled; checklist statuses remain unchanged.
