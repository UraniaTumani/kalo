# Private document storage boundary

Domain services and controllers now depend on `DocumentStorage`, rather than
the local filesystem implementation. This applies to company/driver/vehicle
uploads, private downloads, renewal, review and rollback cleanup.

The contract owns the 5 MB size constant and the `StoredDocument` result type.
Implementations store validated PDF/JPG/PNG bytes under opaque references and
read only their supported stored references. Authorization remains with the
existing owner/company/role checks in domain services, before accessing bytes.
Cleanup remains best effort so a storage cleanup failure does not mask the
original database failure. Provider-specific validation and exceptions retain
their existing behavior in this first step.

`LocalDocumentStorage` remains the single Spring implementation. Its profile
guard still refuses uploads/reads in production or outside dev/test; mixed prod
and dev profiles remain refused. Existing `local:` references, signature checks,
path checks, randomized names, size limit and private attachment responses are
unchanged. Java references to `LocalDocumentStorage.StoredDocument` and its size
constant still resolve to inherited interface members for existing test callers.
Driver/vehicle service unit tests now mock the contract directly.

This is the first step toward production storage. It does not introduce a cloud
provider, bucket, credentials, network calls, public URLs or a data migration.
Production document uploads remain unavailable until a durable adapter is
implemented, configured and verified. No production readiness is claimed.

The next adapter must preserve content/size checks, private access and rollback
cleanup; define explicit timeouts and safe error mapping; avoid logging secrets;
and account for existing `local:` and historical external references. Existing
files must not be silently reinterpreted as cloud object keys. Infrastructure
retention, encryption, backup and recovery need their own deployment evidence.

Validation from the repository root:

```powershell
./mvnw.cmd "-Dtest=LocalDocumentStorageTest,DriverDocumentServiceTest,VehicleDocumentServiceTest,FleetDocumentReviewIntegrationTest,FleetDocumentRenewalIntegrationTest" test
./mvnw.cmd test
cd frontend
node e2e/run-invitation-integration.mjs
```

Existing storage tests cover accepted/rejected bytes, size limits, unsafe paths,
private reads and profile restrictions. Existing real-backend renewal tests cover
both owners, stored byte equality, private downloads and the review transaction.
Preparation validates patch application and whitespace only. Application
compilation and Maven/Docker tests require local/CI confirmation.
