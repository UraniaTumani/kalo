# Bounded report reconciliation — pending local verification

Report scheduling remains absent by default. Explicit enablement requires both
app.driver-invitation.report-scheduler-enabled=true and report-check-enabled=true,
plus a configured trusted reader bean. No live reader or credentials are added.
The report scheduler uses advisory lock 4612005, default initial delay two minutes,
and default polling delay one minute. It processes ten entries per batch by
default (report-batch-size, range 1..50).

Migration 031 adds persisted report_checks, next_report_check_at and
report_stop_reason metadata. Every lookup is reserved under a queue-row lock in a
REQUIRES_NEW transaction before provider HTTP begins. Concurrent reservations
cannot consume the same due slot; a crash still consumes a bounded attempt.
Unknown evidence remains REVIEW and never schedules a send.

Automatic lookups are limited to twelve. Delays start at five minutes, double,
and cap at six hours. The twelfth reservation is the final permitted lookup;
without confirmed evidence the entry stays REVIEW/ATTEMPT_LIMIT. Automatic
checks also stop at 47 hours after queue creation, leaving REVIEW/AGE_LIMIT.
Using queue creation is conservative: it may stop earlier than provider log
retention if a send was delayed. No historical send time is fabricated.
Legacy REVIEW rows start with zero checks and use their existing created_at.

Next-report timestamps survive restarts. Confirmed acceptance/rejection clears
future scheduling and the stop reason, preserving the lookup count. The normal
send scanner still excludes REVIEW, including entries whose checks have stopped.
The internal one-entry checker remains available for separately authorized manual
investigation; automatic bounds are enforced by the reservation/sweep path.

Operations can identify unresolved entries without accessing tokens:
SELECT id, provider_message_id, report_checks, report_stop_reason
FROM driver_invitation_deliveries WHERE state = 'REVIEW';
No operator UI, public endpoint, live configuration or activation is introduced.

Seventeen new tests cover pacing, limits, configuration, locking and persisted
reservation behavior. Expected complete suite: 619 tests and 37 changesets,
subject to local verification. Prior user-confirmed suite: 602 passed, no
failures/errors/skipped, BUILD SUCCESS on 2026-10-25. Checklist statuses unchanged.
Rollback removes pacing metadata and its index; release/rollback operational
validation remains a separate gate. No production-readiness claim is made.
