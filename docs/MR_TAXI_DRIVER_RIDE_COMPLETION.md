# Driver ride completion

POST /api/v1/driver/me/rides/{rideId}/complete accepts the existing
CompleteRideRequest JSON: {"finalAmount":800.50}. Authentication and ownership
use the same locked authorization path as driver progression. Only IN_PROGRESS
can complete. Invalid, skipped, duplicate or terminal operations cannot overwrite
the stored amount or completion time. A duplicate returns 400, not cached success;
this is not an idempotency-key implementation.

RideCompletion is shared with the partner service. In one transaction it stores
the positive taximeter amount, completedAt and COMPLETED status, retains SELECTED
on the originating request and returns the driver ONLINE, matching existing
partner behavior. This does not prove payment collection or generate a ledger.
No migrations, payment provider or frontend changes are included.

The driver entity is refreshed after authorization locks to avoid writing stale
pre-lock JPA values. The ride lock serializes competing completion requests.

Validation pending on the owner's Java/Docker environment:

    .\mvnw.cmd -Dtest=DriverRideCompletionIntegrationTest test
    .\mvnw.cmd test

Eight tests cover successful persistence, invalid amounts, duplicates, wrong
states, foreign ownership, revoked identity, roles and concurrent completion.
Full regression is necessary because partner completion now shares this code.
