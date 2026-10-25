# Driver invitation SMS adapter — pending local verification

This additive step supplies an unwired Infobip v3 transport, separate from the
existing password recovery SmsSender. There is no provider bean, scheduler,
credential, live send, public issuance endpoint, or activation page in this change.
The worker remains disabled by default. Checklist statuses are unchanged.

The transport posts one destination and a stable custom messageId. It requires
matching messageId/destination and a recognized per-message status before reporting
provider acceptance. HTTP 200 alone is insufficient. Acceptance does not certify
handset delivery. HTTP 408/429/5xx, network failures and unknown responses are
retryable; explicit terminal rejection and other HTTP errors stop the attempt.
Duplicate-message rejection is permanent, not evidence of delivery: reconciliation
with provider reports remains unfinished. No exactly-once claim is made.

The future factory uses HTTPS, an Infobip account host, a two-second connection
and five-second read timeout, and an external API key. It creates no bean by itself.
The token is placed in a URL fragment, never a query parameter. The future
activation page must read and immediately clear that fragment before analytics or
third-party scripts, and enforce the server-side single-use activation contract.
No raw request, response, token, destination or provider exception is logged by
this adapter. Application-wide HTTP tracing must also be reviewed before enabling.

Sixteen tests use MockRestServiceServer and no real credentials/network.
Expected complete suite after this patch: 552 tests, subject to verification.
Live provider validation and SMS delivery reports remain outstanding.

Primary references consulted 2026-10-24:
- https://www.infobip.com/docs/tutorials/send-your-first-sms-message-using-infobip-api
- https://www.infobip.com/docs/tutorials/receive-sms-delivery-report-using-infobip-api
- https://www.infobip.com/docs/essentials/api-essentials/response-status-and-error-codes
