# Browser document flow with S3

Baseline: main `e6d2623`, confirmed by the owner after checklist reconciliation.
This adds an optional S3 mode to the existing disposable browser test runner.
Production application code and database migrations are unchanged.

## Run

With Docker Desktop running, from `frontend`:

```powershell
node e2e/run-invitation-integration.mjs --s3
```

The runner combines `docker-compose.invitation-e2e.yml` with the S3 override.
LocalStack exposes no host port. Its dedicated initialization container creates
the emulator bucket before the backend starts. The backend selects the actual
S3 adapter with fake SDK credentials and an internal HTTP test endpoint.
Neither emulator container mounts developer files, cloud credentials or Docker
sockets. There is no persistent object-store volume.

The same three browser tests run: driver invitation/ride flow and document
renewal for driver and vehicle. Renewal checks that both uploaded references
use the selected provider and distinct UUID keys, refusing a silent local
fallback. It exercises UI renewal, authenticated download with exact bytes,
old/new history, admin approval and audit attribution, and anonymous refusal.
HTTP, PostgreSQL and S3 SDK operations are real. SMS and browser GPS remain
simulated; the S3 service is a local emulator.

The original local mode remains:

```powershell
node e2e/run-invitation-integration.mjs
```

Runner arguments are validated before any Compose cleanup. The expected
provider is explicitly passed to the Playwright child process for each mode.
The existing project name and loopback ports 18881/5177 are retained, so modes
must run sequentially. Both modes clear only the named disposable
`kalo-invitation-e2e` project before/after execution. Never attach real databases
or evidence volumes to this fixture project. Failure logs include the backend
and emulator/init services; traces, screenshots and videos remain disabled.

Preparation checks: JavaScript syntax, YAML structure, patch application and
whitespace. Docker/browser execution is pending local or CI confirmation; no
new passing test count is claimed. The previous backend suite had 817 passing
tests. No real-cloud IAM/privacy, retention, recovery or production readiness
is certified by this emulator run. Transaction rollback cleanup remains covered
by `S3DocumentFlowIntegrationTest`; this browser suite does not inject rollback.
