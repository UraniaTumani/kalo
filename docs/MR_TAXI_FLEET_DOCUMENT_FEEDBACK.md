# Explanations for refused fleet document eligibility

Document eligibility remains unchanged. A refusal now returns HTTP 400 with
`code: FLEET_DOCUMENTS_REQUIRED` and up to four `documentIssues`, each containing
only `ownerType`, `documentType` and `reason`. Existing error fields remain.
The response is `Cache-Control: no-store`.

Reasons: MISSING, DRAFT, PENDING, REJECTED, UNAPPROVED, EXPIRY_MISSING, EXPIRED,
FUTURE_ISSUE_DATE. Only the newest version is described. When multiple defects
affect one document, status takes precedence over expiry, then issue date.
Success retains the existing single count query; diagnostics perform one extra
current-version read only on refusal, inside the existing company transaction.

The partner's shared error component renders translated reasons and directs
the partner to the owner/assigned vehicle's Documents panel. The driver's
availability screen renders the same reasons with instructions to contact the
company. Neither screen automatically retries ONLINE or changes eligibility.
Feedback describes the failed request; it is not a live readiness certificate.
Driver feedback resets after a successful explicit/periodic status refresh.
Other availability blockers retain their existing handling.

No IDs, document numbers, dates, storage references or administrator rejection
notes are added to the diagnostics. The client accepts only known owner/type/
reason combinations; unknown or malformed issue arrays display generic document
guidance without rendering the backend message. Existing authorization runs
before the eligibility check; no new lookup endpoint is exposed.

Validation:

```powershell
./mvnw.cmd "-Dtest=FleetDocumentBlockersIntegrationTest,DriverSelfAvailabilityIntegrationTest,RideSearchIntegrationTest,RideLifecycleIntegrationTest,FleetMutationConcurrencyIntegrationTest" test
./mvnw.cmd test
cd frontend
npm run build
npm run lint
npx playwright test --config playwright.invitations.config.ts
node e2e/run-invitation-integration.mjs
```

Added backend tests cover four reasons together, newest-version selection,
tenant isolation and OFFLINE. Eight browser scenarios cover partner/driver,
Albanian/English, mobile/desktop, safe handling of malformed data and clearing
old feedback after reload. The real driver journey checks the diagnostic body.
Static patch/JSON checks were performed during preparation; runtime results
must be confirmed locally/CI.
