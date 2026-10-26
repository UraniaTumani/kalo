# Invitation browser scenario with the real backend

Baseline: user confirmed 694 backend tests, six frontend helper tests and 12 mocked
invitation browser tests passing. The UI and its CI checks are merged. This step adds
one separate real-backend browser scenario, not a new JUnit test or production feature.

## Run

From frontend with Docker Desktop and Chrome available:

    node e2e/run-invitation-integration.mjs

The first run builds a dedicated Java test image and may take several minutes.
Expected result: one passing browser scenario, pending execution. Normal frontend
5173 and backend 8080 can remain running. This stack uses frontend 5177 and backend
127.0.0.1:18881, its own Compose project kalo-invitation-e2e, and an unexposed database
kalo_invitation_e2e stored in tmpfs. Startup and cleanup remove ONLY this named test
project's containers/volumes. Do not run two copies of this scenario simultaneously.
The normal developer DB and standard kalo-e2e stack are not referenced.

The test creates a throwaway driver through the real partner API, queues an invitation
through the real partner UI, waits for the real delivery worker, opens the captured
link in a fresh browser context, activates through the real API, checks token reuse
returns 400, logs in as DRIVER, checks the real partner endpoint returns 403, and
signs out. Browser API calls are not mocked. The simulated delivery transport is the
only substituted business boundary; no actual phone possession or provider behavior
is proven by this scenario.

## Test-only transport and mailbox

InvitationBrowserApplication lives under src/test/java/e2e/invitation, outside the
com.kalo component scan. Only its explicit launcher registers a random per-run cipher,
an in-memory accepted transport and a one-read test mailbox under the existing auth
namespace. Neither the launcher nor mailbox is present in the production jar. There
is no production code or security configuration change. The launcher refuses any
DB_URL other than the dedicated Compose service/database and requires the exact
profiles dev,invitation-e2e. The fixture backend is published on host loopback only;
a standard bridge allows Docker Desktop to publish that loopback port. SMS is
simulated by the explicit test transport, rather than blocked by networking. Do not reuse
or deploy the test image: the mailbox deliberately reveals throwaway invitation
credentials to the test client. The real provider adapter is never registered.

Only the fixture Compose enables issuance, activation and the worker/scheduler.
It explicitly disables report checks and keeps the PENDING foundation off. Production
and regular local flags stay unchanged. Test encryption keys are random and live only
for the lifetime of this disposable stack. No SMS credentials or external secrets are
required. Normal development seed users are recreated in the isolated database.

Playwright disables trace/video/screenshots for this credential-bearing test. The
runner cleans up after normal completion or errors; if forcibly terminated, clean
only this project from the project root with:

    docker compose --project-name kalo-invitation-e2e -f docker-compose.invitation-e2e.yml down --volumes --remove-orphans

The test build replaces frontend/dist; rebuild with normal flags before reusing dist
for another purpose. No deployment is performed.

## Continuous integration

The existing Browser tests job runs this scenario after the mocked invitation tests
and before the general browser suite. It reuses the installed Node 24 and Chrome.
The runner builds the dedicated test image, starts its disposable PostgreSQL/backend,
runs Playwright and cleans up its own Compose project. A failure fails Browser tests
and therefore the existing Regression gate. No live SMS credentials are needed.

## Verification status

Verified locally on Windows with Docker Desktop: 1 passed (17.2s), including partner
invitation, simulated delivery, activation, rejection of token reuse, driver login,
refusal of partner access and sign-out. Test containers and network were removed.
The CI workflow addition still needs its first GitHub Actions run.
