# Invitation report reader — pending local verification

This step adds an unwired Infobip GET /sms/3/logs reader and an internal one-entry
report check. It does not consume /sms/3/reports. Official Infobip documentation
states logs are non-consumptive and retained for the last 48 hours, whereas
retrieved delivery reports are consumed. Missing/expired log records remain
UNKNOWN; absence is never proof that a send failed and never triggers resending.

The request filters by exact provider message ID and asks for at most two rows.
Exactly one matching message ID, destination and configured sender is required,
with a recognized per-message status. Multiple, paginated, malformed or unrelated
results and duplicate-ID rejection remain UNKNOWN. Valid provider evidence is
passed to the existing locked reconciliation service. ACCEPTED still does not
claim handset delivery. Neither status update grants activation or driver access.

Provider I/O runs outside a database transaction. Applying confirmed evidence
uses the existing independent transaction and validates correlation again.
HTTP credentials come only from future external configuration. The factory
restricts HTTPS Infobip account hosts and bounds connection/read timeouts to 2/5
seconds. There is no Spring reader bean, report scheduler, public API or webhook.
app.driver-invitation.report-check-enabled defaults to false. The existing send
scheduler remains disabled and no real configuration or secrets are supplied.
Provider log bodies may include the original secret message: no response body,
content field, destination or exception is logged or persisted by this reader.

Twenty new tests use mocked HTTP and PostgreSQL fixtures; no real provider is
contacted. Expected suite: 602 tests; migrations unchanged at 36 changesets.
Compilation and tests require local verification. Prior verified suite: 582 tests,
0 failures/errors/skipped, BUILD SUCCESS reported by user on 2026-10-25.
No master checklist statuses are changed.

Primary references consulted 2026-10-25:
- https://www.infobip.com/docs/reporting/webhooks (logs vs consumed reports)
- https://www.infobip.com/docs/reporting/message-logs (retention and limits)
- https://www.infobip.com/docs/api/channels/sms/logs-and-status-reports/get-outbound-sms-message-logs-v3
- https://github.com/infobip/infobip-api-java-client/blob/master/src/main/java/com/infobip/api/SmsApi.java
- https://github.com/infobip/infobip-api-java-client/blob/master/src/main/java/com/infobip/model/SmsLog.java
- https://github.com/infobip/infobip-api-java-client/blob/master/src/main/java/com/infobip/model/SmsLogsResponse.java

Remaining before live delivery: actual account contract tests and read permissions,
a bounded reconciliation scheduling/operations path, safe deployment wiring and
contact/activation controls. No production-readiness claim is made.
