# Driver activation API boundary

Baseline: user confirmed 668 tests passing on 2026-10-25. This step adds 11 tests;
expected total 679, pending a full local Maven run. No database migration.

POST /api/v1/auth/driver/activate accepts a JSON object with token and password.
The token is the invitation bearer credential; no login is required. The existing
atomic activation service selects the phone, company and DRIVER role from stored
invitation data. Success returns 204 with no body and Cache-Control: no-store.
Login is a separate request. Tokens must stay in the request body, not the URL.
Invalid or consumed invitations return 400. Passwords require at least 8 characters
and at most 72 UTF-8 bytes. Request.toString redacts credentials.

The controller exists only with app.driver-invitation.activation-api-enabled=true.
Startup then also requires app.driver-invitation.activation-enabled=true.
Both flags default false; this patch changes no deployment configuration.
The route inherits the anonymous auth path security rule. The existing limiter
caps this exact path at 10 requests per IP per minute, including malformed requests.
It is per application instance, resets on restart, and uses existing forwarded-IP
trust configuration. A production edge limiter and operational rollout remain open.

Tests cover anonymous success followed by login, single use, invalid tokens,
password character/byte limits, missing/malformed bodies, body-only credentials,
GET refusal, disabled route and rate limiting, startup guard and credential redaction.
Existing service tests cover expiry, revocation, tenant eligibility and concurrency.
No live SMS, keys, public invitation issuance, frontend activation page, deployment,
commits, checklist completion or rollout approval is included.
