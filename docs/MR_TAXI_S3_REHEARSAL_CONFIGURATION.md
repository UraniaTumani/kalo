# S3 configuration for the production-shaped rehearsal

Baseline: main `9b307ce`. The owner supplied successful CI run
https://github.com/UraniaTumani/kalo/actions/runs/42403752670
with Backend tests, Frontend build, Load harness tests, Build images, Browser
tests, Mobile Safari and Regression gate green (14 minutes 20 seconds).
This is CI/emulator evidence, not a real-cloud storage test.

The base `docker-compose.prod.yml` did not forward the document S3 settings.
The optional `docker-compose.s3.yml` override supplies them without changing
the default rehearsal. It requires bucket and region at Compose interpolation
time and selects S3 explicitly. It provisions no bucket or identity.

## Prepare without starting services

Use an untracked environment file outside the repository, based on the existing
production example with independent rehearsal secrets. Add these non-secret
settings with actual values before validation:

```dotenv
DOCUMENTS_S3_BUCKET=your-dedicated-rehearsal-bucket
DOCUMENTS_S3_REGION=your-region
DOCUMENTS_S3_PREFIX=documents/
DOCUMENTS_S3_PATH_STYLE=false
# Leave blank for AWS; compatible providers need their HTTPS origin.
DOCUMENTS_S3_ENDPOINT=
```

The existing SDK default credentials chain remains in use. This override can
forward externally supplied access key/secret and optional session token.
It does not mount host credential files, configure workload roles or provide
secrets management. Prefer deployment identity where available; its setup
depends on the chosen platform. Never commit credentials or paste them in chat.

Validate from the repository root, replacing the environment file path:

```powershell
docker compose --env-file "C:\path\outside-repo\rehearsal.env" -f docker-compose.prod.yml -f docker-compose.s3.yml config --quiet
```

Use `--quiet`: ordinary rendered Compose output can expose secret values.
This validates configuration only; it does not start services, access S3 or
prove credentials/IAM. Do not start the stack until bucket privacy, encryption,
credentials, existing references and the target database have been reviewed.
The base rehearsal exposes ports 8082 and 5434; this override does not turn it
into a hardened internet deployment. Never use its disposable-volume cleanup
instructions against a real database.

Before a storage switch, migrate/verify existing local references explicitly
or use a new dedicated rehearsal database. Upload, private download, renewal,
review, restart/recovery and denied-access checks against the actual provider
remain pending. See `MR_TAXI_S3_DOCUMENT_STORAGE.md` for retention, migration,
orphan cleanup and IAM limitations.

Preparation validation covers YAML structure, required-variable guards and
patch application/whitespace. Docker configuration validation and cloud
execution are not claimed in the implementation environment.
