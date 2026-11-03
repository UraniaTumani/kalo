# Fleet review attribution and administrator history

New approve/reject decisions store `reviewed_by_user_id` and `reviewed_at` on
the exact document version, in the same transaction as status/rejection reason.
The actor is resolved from authenticated server context and must still be an
active ADMIN. Clients cannot supply actor or time. Times are stored in UTC;
the administrator UI renders the time in the viewing device's local timezone.

Migration 032 adds nullable columns, a reviewer FK with RESTRICT on deletion,
an index and a paired-attribution/terminal-status constraint. A PostgreSQL
trigger prevents changing a recorded reviewer/time/status/rejection reason.
Existing decisions remain unattributed; no user or timestamp is guessed.
Renewal creates a new version with empty review metadata; the previous version
retains its reviewer, timestamp and decision. Repeated decisions remain refused.

The administrator list/decision response adds `reviewedByUserId` and `reviewedAt`.
`GET /api/v1/admin/partners/{companyId}/fleet/{ownerType}/{ownerId}/documents/history`
returns all versions newest-first after existing ADMIN and owner/company checks.
It uses no-store and excludes file storage references. Private downloads still
use the existing owner-scoped file endpoint. The UI history mode is read-only,
including old DRAFT/PENDING versions; decisions remain on current documents.

Only administrators receive reviewer attribution. Partner/driver DTOs and
document-blocker feedback do not expose it. The UI shows stable administrator
IDs, not personal contact details. Historical decisions without metadata are
explicitly labelled unknown. Failed refreshes hide previous document rows.

This is per-version attribution for the existing single terminal decision rule.
It is not a general append-only event ledger, deletion-retention policy or
protection from privileged database operators. File evidence immutability and
production durable storage remain separate work. Removing this migration would
remove audit metadata; retain backups and review rollback before a real rollout.

Validation from the repository root:

```powershell
./mvnw.cmd "-Dtest=FleetDocumentReviewIntegrationTest,FleetDocumentRenewalIntegrationTest,FleetDocumentBlockersIntegrationTest" test
./mvnw.cmd test
cd frontend
npm run build
npm run lint
npx playwright test --config playwright.invitations.config.ts
node e2e/run-invitation-integration.mjs
```

New backend cases cover attribution, atomic rollback, version history,
roles/ownership, the database update guard and unknown historical decisions.
Responsive browser cases cover recorded/unknown attribution, read-only history,
failed refresh and returning to current documents. The real driver/vehicle
renewal tests verify attribution through authenticated HTTP and PostgreSQL.
Preparation checks validate patch application, whitespace, YAML and locale JSON;
runtime execution still requires local/CI confirmation.
