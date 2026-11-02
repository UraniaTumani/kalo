# Required fleet evidence before new work

Business decision confirmed by the owner on 2026-11-02: the four baseline
documents are mandatory. There is no grandfather exemption or test bypass.

| Owner | Required current document |
|---|---|
| Driver | DRIVING_LICENSE |
| Vehicle | VEHICLE_REGISTRATION |
| Vehicle | INSURANCE |
| Vehicle | TECHNICAL_INSPECTION |

Only the newest ID per owner/type is current, matching renewal/history rules.
Each required document must be APPROVED, have a non-null expiry on or after
today in Europe/Tirane, and have no future issue date when supplied. Missing,
DRAFT, PENDING, REJECTED, expired and undated evidence cannot qualify. An older
approved version cannot qualify after a newer pending version is created.
Operational driver/vehicle dates, company status, assignments, GPS freshness
and existing authorization checks still apply independently. Approval does not
silently overwrite the operational expiry fields.

The rule applies to partner ONLINE, driver self ONLINE, both taxi-search and
dashboard eligibility queries, and acceptance of a ride. Acceptance rechecks
after acquiring the company gate; search results are only a snapshot.
Initial fleet uploads now use that same gate, as renewal and review already do.
The fresh JDBC document read avoids stale JPA entities after waiting for locks.

OFFLINE remains permitted subject to existing authorization/BUSY rules.
Expiry does not cancel or prevent progression/completion of an existing ride.
Completion retains its existing ONLINE transition, but search and subsequent
acceptance still enforce document eligibility. No scheduler is added to rewrite
availability merely because a document expires.

Existing fleets without approved evidence will stop qualifying for new work.
Operators must upload and obtain approval; data is not automatically marked
approved. The development seeder remains subject to this rule. Insurance here
means the existing INSURANCE document type; passenger coverage, professional
certificates and other requirements remain separate checklist work.

Backend fleet assignment test fixtures explicitly provision approved metadata.
These repository fixtures do not prove file storage. Browser fixtures instead
upload real PDF bytes and approve them through authenticated APIs, including
the existing driver invitation journey. Legacy browser warm-up provisions the
two disposable seeded fleets through the same APIs. Production configuration
and production storage are not changed.

Validation commands from the repository root:

```powershell
./mvnw.cmd "-Dtest=DriverSelfAvailabilityIntegrationTest,RideSearchIntegrationTest,RideLifecycleIntegrationTest,FleetMutationConcurrencyIntegrationTest,DriverDocumentServiceTest,VehicleDocumentServiceTest" test
./mvnw.cmd test
cd frontend
npm run build
npm run lint
node e2e/run-invitation-integration.mjs
npm run test:e2e
```

Tests cover all four requirements, refused verification states, expiry/date
boundaries, a newer pending version, partner/self ONLINE, search/dashboard,
acceptance after changed evidence, and acceptance waiting on the company gate.
Patch application/whitespace checks were performed during preparation;
Maven, Docker and browser results require local/CI confirmation.
