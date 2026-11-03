# S3 document flow integration

Baseline: user-confirmed main `6c15264` (S3 adapter merge).

`S3DocumentFlowIntegrationTest` exercises the production Spring MVC routes,
security filters, document services, PostgreSQL transactions and S3 storage
adapter together. Requests use MockMvc, not a listening HTTP server or browser.
The SDK sends real HTTP requests to a disposable LocalStack container.

## Isolation

- Extends the existing PostgreSQL Testcontainers integration base.
- Explicitly selects `app.documents.provider=s3`; asserts one storage bean,
  an actual `S3DocumentStorage`, with no local fallback.
- Overrides only the `documentS3Client` bean using Spring `@TestBean` with
  `enforceOverride=true`; the production storage bean is still constructed.
- Uses fake static emulator credentials, a mapped container endpoint and a
  dedicated bucket. It never resolves developer credentials or contacts AWS.
- Creates its bucket before services use it. Closes its SDK client and stops
  its object-store container after the class; dirties its Spring context so
  later classes cannot reuse a stopped endpoint. PostgreSQL retains the
  existing suite-wide lifecycle.
- No production configuration, dependencies or database migrations change.

## Coverage

Eleven invocations: one wiring test and five scenarios for both DRIVER and
VEHICLE owners.

1. Authenticated upload creates DRAFT metadata and real S3 bytes. Signature
   detection wins over the misleading filename and MIME type. Private download
   returns exact bytes, PDF content type, attachment, no-store and nosniff.
2. Admin rejection, partner renewal and admin approval preserve both files.
   The latest list contains only the replacement; history contains both rows.
   Reviewer identity and time persist. Partner and admin can download both
   versions through their authorized routes.
3. A foreign partner cannot upload, renew or download against another company;
   a customer cannot download, a partner cannot approve, and anonymous download
   is refused. Object inventory and document count remain unchanged.
4. An outer transaction rollback after upload removes the flushed database row
   and S3 object. The object is verified present before rollback and absent by
   direct SDK HEAD afterward; the bucket inventory returns to its prior state.
5. Renewal rollback removes only the new row and object. Original evidence,
   rejection reason and prior bucket inventory survive.
6. Deleting S3 evidence before approval causes refusal and leaves DRAFT metadata
   without reviewer identity or time.

Items 1 and 2 are one combined scenario per owner; items 3–6 are four more.
The tests deliberately use a rejected document to renew without waiting for
expiry. Upload and renewal rollback tests control a real outer transaction,
matching the existing local-storage rollback integration pattern.

## Run

With Docker Desktop running, from the repository root in PowerShell:

```powershell
.\mvnw.cmd "-Dtest=S3DocumentFlowIntegrationTest" test
.\mvnw.cmd test
```

Implementation-environment validation is static only: no Java compiler,
Maven dependency cache or Docker execution is available here. Runtime results
must be recorded after these commands run on the developer machine or CI.

This verifies application-level authorization and transaction cleanup against
an emulator. It does not certify AWS IAM, bucket public-access settings,
versioned-object retention, cloud availability or durability. Existing browser
integration tests still use local storage. Cleanup after a successful PUT and
known transaction rollback is covered; uncertain PUT outcomes, failed deletes
and orphan reconciliation remain outside this test's proof.
