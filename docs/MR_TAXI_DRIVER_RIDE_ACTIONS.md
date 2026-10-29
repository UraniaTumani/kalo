# Driver ride actions: first three transitions

POST /api/v1/driver/me/rides/{rideId}/driver-arriving
POST /api/v1/driver/me/rides/{rideId}/driver-arrived
POST /api/v1/driver/me/rides/{rideId}/start

These require an eligible linked DRIVER account and the ride's assigned driver
and company. Another driver's ride returns 404. Role restrictions are enforced
by the existing SecurityConfig driver route rule. Successful responses contain
only rideId and status, with Cache-Control: no-store.

RideProgression owns the three status/timestamp changes and is used by both
partner and driver services. Existing partner error messages are preserved.
Driver actions lock the scoped ride first, matching partner/cancellation order,
then company, driver and user. Fresh JDBC reads recheck binding and eligibility
after locks; pre-lock JPA identity data is not the final authorization decision.

Skipped, repeated and terminal transitions return 400. A lost response must be
resolved by reading the current ride before retrying. This is not a complete
idempotency-key implementation. No complete/payment or UI changes are included.

Validation pending on the owner's environment:

    .\mvnw.cmd -Dtest=DriverRideActionIntegrationTest test
    .\mvnw.cmd test

Eight new tests cover progression/timestamps, skip/repeat, foreign driver/company,
terminal states, role restrictions, revoked binding and concurrent duplicates.
The full suite is required because partner services now share progression rules.
