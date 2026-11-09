# Local PostgreSQL backup and restore rehearsal

This is a first exercise for MRT-12-06. It checks a PostgreSQL 17 logical
backup and restore with synthetic data on the same major version as the
Compose rehearsal. It uses a new container, no published ports, and a
temporary host directory. It needs Docker Desktop and no AWS credentials.

From the repository root in PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-postgres-backup-restore.ps1
```

The script creates a source database and a separate empty destination
database in the temporary container. It dumps the source in PostgreSQL
custom format, copies the backup out of the container, and restores
the copied archive into the destination in one transaction. It checks
the restored rows, relationship, amounts and the next generated ID.
Expect a final line starting with `PASS:`. Errors fail the run. The
script removes the temporary container, its anonymous volume and the
backup file on normal completion or handled failure.

This exercise does not use the Kalo application schema or test Liquibase
rollback, S3 recovery, scheduled backups or staging. Keep MRT-12-06 open
until a staging backup has been restored to an isolated target, verified
with the application, and the migration rollback procedure has been tested.
