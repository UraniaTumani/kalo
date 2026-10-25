# Invitation issuer audit — pending local verification

Migration 029 adds a nullable issued_by_user_id foreign key to users. Historical
rows remain NULL: no attribution is inferred or backfilled from current ownership.
New issuance records the owner ID of the company resolved and locked through
PARTNER authorization. Neither actor nor company ID is accepted as an issuance
argument. recordIssuer rejects invalid IDs and reassignment; the JPA column is
updatable=false. This is application/ORM protection, not a database tamper-proof
or append-only audit system. Company ownership and profile changes do not rewrite
the actor ID recorded for past invitations.

Six new tests cover write-once identity, invalid IDs, actual issuance attribution,
legacy rows, FK rejection and stability across user-profile updates. The existing
persistence unit test additionally checks issuer recording.
Expected full suite: 572 tests and 35 Liquibase changesets, subject to verification.

Verified local suite reported by the user before this change: 566 tests,
0 failures/errors/skipped, BUILD SUCCESS, 2026-10-24 22:01:17 +02:00.
This patch does not modify master checklist statuses or certify production.

Remaining issuance gates include contact/activation verification, duplicate-message
reconciliation, safe key/provider configuration, and negative endpoint tests.
No public endpoint, live SMS configuration, or scheduler enablement is added.
