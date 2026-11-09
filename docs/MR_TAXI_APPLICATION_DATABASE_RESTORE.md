# Local restore with the Kalo application schema

This optional exercise extends the synthetic PostgreSQL 17 backup rehearsal
for MRT-12-06. Run it from the project root with Docker Desktop running:

```powershell
.\mvnw.cmd "-Dtest=PostgresApplicationRestoreRehearsalIT" test
```

The source is the standard disposable PostgreSQL integration test container.
Liquibase applies the application migrations, and the test creates a fixture
customer. It dumps the source database and restores the archive into a
different database in that container. A second instance of the application
listens on an ephemeral loopback port, validates the restored schema with
Liquibase disabled, and must log in the restored customer and read their
profile through the authenticated HTTP endpoint. The test also verifies the
target database name and the restored Liquibase history row count.

Expect one passing test and Maven `BUILD SUCCESS`. The test name keeps this
exercise out of the default Surefire test selection. No existing application
database or cloud bucket is needed. The test cleans up its target database and
temporary archive; it does not retain a backup.

This verifies a local logical restore of the application schema and one
authentication/read path. Staging recovery, object storage recovery,
scheduled backups and migration rollback remain separate checks.
