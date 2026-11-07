# S3 browser checks in CI

Baseline: main `dfc947b`, confirmed by the owner's fast-forward log.
Local evidence before this change: 3 S3 browser tests passed in 38.3 seconds,
and 3 local-storage browser tests passed in 29.7 seconds on 2026-11-07.
The last confirmed backend suite remains 817 passing tests; it is not rerun
or recounted by this documentation update.

## Change

The existing `Browser tests` job in `.github/workflows/ci.yml` now runs:

1. Invitation helper tests and mocked UI checks, as before.
2. The real-backend invitation/document suite with local storage, as before.
3. The same suite with `node e2e/run-invitation-integration.mjs --s3`.
4. A fallback cleanup step with `if: always()`.
5. The general browser suite, as before, if earlier steps succeeded.

Local and S3 integration steps execute sequentially on the same runner.
They intentionally use the same disposable project and loopback ports; they
must not be backgrounded or run together. Each integration step has a
20-minute timeout. The cleanup step has a 3-minute timeout and addresses only
the `kalo-invitation-e2e` project using its base and S3 override Compose files.
It removes disposable fixture volumes, never developer database volumes.

The runner's own finally block remains the normal cleanup path. The workflow
fallback handles a step timeout that terminates Node before finally completes.
Runner loss or forced job termination can still prevent cleanup; hosted runners
are disposable. This does not introduce cleanup commands for real environments.

The S3 step has no `continue-on-error`, so its failure fails the `e2e` job.
The existing `Regression gate` already requires the `e2e` result to be success;
no additional job or required-check name is introduced. Whether repository
branch protection actually requires that gate must still be verified in GitHub
settings; workflow source alone does not prove enforcement.

## Boundaries and validation

No cloud account, deployment secrets, public S3 endpoint or production bucket
is used. The same LocalStack image and fake credentials from the locally tested
Compose override are used. SMS/GPS stay simulated. This is real browser/API/DB
and emulator evidence, not certification of AWS IAM, durability or staging.
Existing trace/screenshot/video restrictions remain in the integration config.

Preparation checks cover YAML structure, step ordering, timeout/cleanup scope,
the unchanged regression dependency and patch application/whitespace. GitHub
Actions execution is pending the pull request run. Record its URL and result
before declaring the new CI check verified. No application code, dependencies,
database migration or frontend product UI changes.
