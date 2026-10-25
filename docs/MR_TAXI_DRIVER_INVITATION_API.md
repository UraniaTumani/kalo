# Partner driver invitation API

Baseline: 679 tests passed locally, confirmed by the user on 2026-10-25.
This patch adds 15 tests; expected full total 694, pending local execution.
No database migration or deployment configuration changes.

POST /api/v1/partner/drivers/{driverId}/invitations requires a PARTNER login.
The controller is registered only when app.driver-invitation.issuance-api-enabled=true
(default false). Opting in requires an externally supplied delivery cipher bean and
valid delivery-key-id; missing encryption configuration fails startup.

The existing transactional preparation service derives the company from the logged-in
owner, locks company then driver, checks tenant ownership and current eligibility,
enforces driver cooldown (60 seconds) and company quota (20 per rolling hour), then
stores the hash and encrypted delivery queue atomically. Issuer identity is derived
server-side. Existing cooldown/quota rejections use 400; missing/foreign drivers 404,
anonymous callers 401 and disallowed roles 403. No company, phone, role, destination,
raw token or account identity is accepted from a request body.

Success is 202 with an empty body and Cache-Control: no-store. This confirms queueing,
not SMS sending, provider acceptance or activation. No token, ciphertext, internal ID,
phone or password is returned. Repeated requests obey the existing quota/cooldown;
clients should not automatically retry on an uncertain response. Sending and report
schedulers remain independently disabled. No live SMS request is made by this patch.

Tests cover owner success and matching encrypted/hash storage, issuer audit, missing
and foreign drivers, anonymous/customer/admin access, inactive or linked drivers,
suspended company, cooldown and a configured company quota of two, malformed IDs,
default-disabled route, and encryption startup guards.

Remaining work includes the partner invitation button, activation page, externally
managed keys, controlled provider testing and an explicit rollout decision. This is
not completion of the Driver Identity checklist item or production readiness.
