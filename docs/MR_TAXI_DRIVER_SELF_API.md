# Driver self identity API

GET /api/v1/driver/me returns the authenticated driver's driverId, companyId,
firstName, lastName, phone, status and availabilityStatus. Cache-Control is no-store.
It resolves the account from the authenticated principal and the persisted User–Driver
link. Query parameters cannot select another driver or company. No ID-based variant
is provided. No password hashes, tokens, licence numbers or documents are returned.

The route requires ROLE_DRIVER. Anonymous requests return 401; authenticated partner,
customer and admin requests return 403. Current account/binding eligibility reuses
DriverAccountAccessPolicy, alongside the existing per-request JWT validation.
Removing the binding, changing the driver's phone, disabling the driver or suspending
or unapproving the company blocks existing tokens on the next request.

This is read-only groundwork for MRT-03-03. It does not implement GPS writes, trip
controls or a native Driver App. MRT-03-03 remains open. No migration is required.

## Verification

Nine integration tests added for self identity, foreign query IDs, role isolation,
anonymous access, revoked eligibility and rejection of ID-based paths.
Run from the repository root on Windows:

    .\mvnw.cmd -Dtest=DriverSelfApiIntegrationTest test
    .\mvnw.cmd test

Execution is pending on the user's Docker/Testcontainers environment. The previous
confirmed backend baseline is 694 passing tests; no new passing total is claimed.
