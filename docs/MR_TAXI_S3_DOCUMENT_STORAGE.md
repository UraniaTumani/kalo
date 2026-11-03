# S3 document storage adapter

The adapter is available by explicit configuration; `local` remains the default
and retains the dev/test profile guard. No cloud resource is provisioned and no
existing document reference is rewritten by this patch. There is no DB migration.

## Configuration

| Environment variable | Meaning |
|---|---|
| DOCUMENTS_PROVIDER | `local` (default) or `s3` |
| DOCUMENTS_S3_BUCKET | Existing dedicated private bucket; required for S3 |
| DOCUMENTS_S3_REGION | Signing region; required for S3 |
| DOCUMENTS_S3_ENDPOINT | Optional HTTPS origin for a compatible provider; empty uses AWS endpoint |
| DOCUMENTS_S3_PREFIX | Safe key prefix ending in `/`; default `documents/` |
| DOCUMENTS_S3_PATH_STYLE | `false` by default; select `true` only when the provider requires it |

Credentials use the AWS SDK default provider chain, including deployment roles,
web identity (STS module included), container/instance credentials or standard
AWS environment variables. Do not commit keys, place them in application config
or send them in chat. Static `test/test` credentials exist only in emulator tests.
HTTP endpoint overrides are allowed only with dev/test and no prod profile.
Endpoint userinfo, query strings, fragments and extra URL paths are refused.
Unknown provider values cannot create a fallback local bean; normal application
service injection then fails. Incomplete S3 settings fail at startup.

The bucket/region/prefix are deployment identity: changing them without moving
the stored objects makes existing references unreadable. There is no automatic
fallback to local storage or to an arbitrary URL supplied in a record.

## Storage behavior

Local and S3 share signature checks for PDF/JPG/PNG and the 5 MB limit. Uploads
are read through a bounded stream even if a multipart size is understated.
Objects use random UUID keys and references such as `s3:<uuid>.pdf`; original
filenames, owner IDs and document numbers are not part of object keys.

PUT requests use `If-None-Match: *`, SSE-S3 `AES256`, attachment content disposition
and no-store metadata. No ACL or public URL is created by the adapter. Encryption
must be confirmed by the provider's response. Providers must support these S3
semantics; compatibility is not claimed for every vendor or encryption mode.
SSE-KMS, directory buckets and Object Lock are not implemented in this adapter.

Reads accept only the configured namespace's generated references. The SDK
response transformer bounds consumption to 5 MB + 1 byte and aborts oversized
responses; size, signature, extension and encryption metadata are checked before
returning bytes. Domain authorization still precedes storage access, and HTTP
downloads retain the existing authenticated attachment endpoints.

The client uses a 3 s connect timeout, 10 s socket timeout, 15 s API-attempt
timeout and 20 s overall API timeout. Credential resolution has its own SDK
provider behavior and must also be checked in the deployment. SDK attempts are capped at one: an uncertain
mutation outcome is not automatically replayed. Provider/network failures return
safe HTTP 503 with no-store and no SDK detail. Missing keys return 404 when the
provider reports 404; permission failures remain 503 rather than leaking details.

Existing transaction callbacks call best-effort `discard` after DB rollback.
Cleanup errors do not mask the original failure. A PUT that committed but lost
its response can leave an unreferenced object; it is deliberately not deleted
after an uncertain conditional write. Periodic reconciliation with a grace
period is a separate operational requirement. No automatic object sweep is added.

## Infrastructure required before a real rollout

Use a dedicated private bucket. Enable all public-access blocks and appropriate
ownership controls, TLS-only access, encryption and versioning/retention suited
to document policy. Verify anonymous access is refused in the chosen provider.
The application does not inspect or change bucket privacy/IAM settings and does
not certify them. The adapter alone does not make a public bucket private.

`docs/examples/document-storage-iam.json` is an AWS identity-policy template.
Replace its bucket placeholder and align `documents/` with the configured prefix.
It permits only object read/write/cleanup in that prefix and bucket listing in
the dedicated document bucket. Listing lets AWS report a missing key as 404;
without ListBucket, AWS may return 403 even for a missing key. The template is
not a bucket policy and grants no bucket-administration or permanent-version
deletion privileges. Apply and verify infrastructure policies outside the app.

Test restart/recovery, retained-version access, backup/restore, access denial,
credentials rotation and alarms in staging. With bucket versioning enabled,
ordinary cleanup creates delete markers; retained versions require a separate
retention policy. Existing `local:` or external-link rows need an explicit,
verified migration before switching their environment to S3.

## Validation

From the repository root with Docker Desktop running:

```powershell
./mvnw.cmd "-Dtest=LocalDocumentStorageTest,S3DocumentStorageTest,S3DocumentStorageConfigurationTest,S3DocumentStorageIntegrationTest,DocumentStorageFailureIntegrationTest" test
./mvnw.cmd test
cd frontend
node e2e/run-invitation-integration.mjs
```

The standalone S3 integration class starts `localstack/localstack:4.10.0` through
Testcontainers, creates only a disposable emulator bucket and uses fake keys.
It checks real SDK/HTTP byte round trips after client recreation, conditional
writes, encryption metadata, cleanup, and rejection of invalid/oversized objects.
It does not prove real-cloud IAM, physical encryption/durability, or privacy.
The existing invitation/browser tests continue using the local provider.
Failure integration tests exercise real authorization and PostgreSQL with a
mocked unavailable storage boundary, including refusal to approve unreadable
evidence. Configuration and unit tests cover provider selection, profile/TLS
guards, bounded input, opaque references and safe provider errors.

Preparation checks cover patch application, whitespace, POM XML and policy JSON.
Application compilation, Docker tests and cloud/staging validation remain to be
confirmed; production readiness is not declared by this patch.

## SDK references

- https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.8
- https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html
- https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html
- https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html
- https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/amazon-s3-policy-keys.html
