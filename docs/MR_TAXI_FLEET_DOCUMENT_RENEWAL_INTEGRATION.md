# Fleet document renewal: real backend browser test

Run from `frontend` with Docker Desktop running:

```powershell
node e2e/run-invitation-integration.mjs
```

The existing runner starts and removes only the disposable
`kalo-invitation-e2e` Compose project. It runs the invitation/driver flow and
two additional document tests, one for a driver and one for a vehicle.
The database is disposable; uploaded files live under `/tmp` inside the
disposable backend container. No developer database or upload directory is used.

Each test verifies the isolated backend marker through both direct and preview
routes before creating records. It logs in through the partner UI, creates its
own owner and uploads the original PDF through authenticated HTTP. Admin
rejection and approval use real authenticated HTTP. Renewal and history display
use the partner UI. Current/history reads verify distinct versions, statuses and
document numbers. Both private files must match their original bytes; anonymous
downloads and approval of the superseded version must be refused.

There are no HTTP route mocks in these tests. PostgreSQL, authorization and
local document storage are real. This does not exercise the admin review UI,
production object storage, staging, or the full cross-company security matrix.
Backend integration tests retain coverage of concurrency and rollback cleanup.

The patch was checked for application and whitespace in the preparation
environment. Docker/browser execution must be confirmed locally and in CI.
