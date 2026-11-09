# Local Liquibase rollback rehearsal

This optional exercise covers changeset `032-fleet-document-review-audit`
for MRT-12-06. Run from the repository root with Docker Desktop running:

```powershell
.\mvnw.cmd "-Dtest=LiquibaseRollbackRehearsalIT" test
```

The test starts its own disposable PostgreSQL 17 instance and applies the
real changelog. It verifies that 032 is the last applied changeset before
rolling back one changeset. If a new migration supersedes 032, the guard
fails before rollback; explicitly retarget the rehearsal before rerunning.
The exercise verifies that the document row remains, that the audit columns
and trigger are removed, and that reapplying 032 reinstates the audit rules.

**Rollback discards the reviewer ID and timestamp.** Reapplying the migration
does not recover them. This test deliberately checks that they are null after
reapply. It is not a procedure for rolling back staging or production; a
recovery plan there must preserve audit data and account for application/schema
compatibility. No real database, document storage, or AWS credentials are used.

Expect one passing test and Maven `BUILD SUCCESS`. This exercise does not
prove rollback for other changesets, nor does it close MRT-12-06.
