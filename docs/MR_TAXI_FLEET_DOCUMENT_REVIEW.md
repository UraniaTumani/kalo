# Fleet document review API foundation

Admin-only base: /api/v1/admin/partners/{companyId}/fleet/{ownerType}/{ownerId}/documents
ownerType is DRIVER or VEHICLE (uppercase). COMPANY is rejected; company review
keeps its existing flow. Owner must belong to companyId; document must belong to
that exact owner/type. Metadata omits storage references.

GET base lists documents, GET /{id}/file downloads a private attachment,
POST /{id}/approve approves, POST /{id}/reject accepts {"reason":"..."}.
Responses use no-store. A nonblank rejection reason up to 1000 characters is
required. Review accepts DRAFT or PENDING because fleet upload currently creates
DRAFT and has no submit endpoint. APPROVED/REJECTED are terminal in this API;
repeated/competing decisions cannot overwrite the first committed result.

A decision takes the company fleet lock and then refreshes/locks the document.
Approval refuses a future issue date, an expired date when supplied, or an
unreadable file. Dates remain optional as in the existing upload contract;
approval does not certify completeness or replace operational expiry checks.
An administrator must inspect the bytes and verify authenticity themselves.

No UI, renewal/resubmission, revocation, automatic ONLINE eligibility enforcement,
migrations or payment changes are included. Persistent reviewer identity/history
is not yet implemented; the existing status/reason are stored and a decision log
is emitted. Durable document storage is still required for production.

Validation on owner environment:

    .\mvnw.cmd -Dtest=FleetDocumentReviewIntegrationTest test
    .\mvnw.cmd test

No test result is claimed until execution completes.
