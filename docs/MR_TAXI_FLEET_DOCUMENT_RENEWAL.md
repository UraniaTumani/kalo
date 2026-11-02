# Fleet document renewal API

POST /api/v1/partner/{drivers|vehicles}/{ownerId}/documents/{documentId}/renew
accepts multipart file, optional documentNumber/issuedAt and required expiresAt.
Only the owner's PARTNER company may renew. The selected ID must be the newest
version of its type and be REJECTED or have a recorded expiry before today.
The new file must pass existing private storage checks. Its expiry must be
present and valid through today in Europe/Tirane, and issue date cannot be future.

A successful request creates a new document ID with the same owner/type and
PENDING status. The old row, decision/reason and file are retained unchanged.
Normal driver/vehicle and admin fleet lists return only the greatest ID per
document type. GET on the partner documents/history path returns all versions,
newest first. Existing scoped file endpoints can download historical IDs.
No file is exposed publicly. No migration or automatic deletion is introduced.
History retention/storage quotas need an explicit production policy.

Renewal and admin decisions share company then document locks. Admin decisions
refuse superseded IDs. Concurrent renewals of the same old ID yield one new row;
a retry of that old ID returns conflict, not another upload. This is not a full
idempotency protocol. It does not add reviewer identity/time history or an admin
history UI. Owner membership writers outside the existing fleet lock are outside
this guarantee. Initial-upload concurrency is unchanged.

No automatic changes to driver licence/vehicle operational expiry fields or
ONLINE eligibility. Approved-document enforcement and renewal UI remain separate.

Validation on owner environment:

    .\mvnw.cmd -Dtest=FleetDocumentRenewalIntegrationTest,FleetDocumentReviewIntegrationTest,DriverDocumentServiceTest,VehicleDocumentServiceTest test
    .\mvnw.cmd test

No execution result is claimed before those commands complete.
