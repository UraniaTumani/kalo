# Fleet document review UI

Admin Companies provides a Fleet documents action on desktop and mobile.
The scoped owner picker lists drivers/vehicles in stable ID order, 20 per page,
using the new admin-only GET /admin/partners/{companyId}/fleet/owners endpoint.
Inactive owners remain visible so historical documents can be inspected.

Review uses the previously merged private download and approve/reject APIs.
Both decisions require confirmation. Rejection requires a nonblank reason
(maximum 1000 characters). Final states have no decision buttons. Requests are
not automatically retried; status is reread after a decision, including failure.
Company/owner selection resets document state. Raw API errors are not displayed.
UI translations are English and Albanian. No storage references are exposed.

No approval-based ONLINE enforcement, renewal, revocation, persistent audit
history or production document storage is added here. API approval/rejection
rules remain authoritative. The owner listing relies on existing /admin/** RBAC.

Validation on the owner environment:

    .\mvnw.cmd -Dtest=FleetDocumentOwnerIntegrationTest,FleetDocumentReviewIntegrationTest test
    .\mvnw.cmd test

From frontend: npm run build; npm run lint;
npx playwright test --config playwright.invitations.config.ts
The new mocked browser tests run in existing CI through that config.
No test results are claimed until those commands complete.
