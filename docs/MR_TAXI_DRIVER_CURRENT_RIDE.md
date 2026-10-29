# Driver current ride API

GET /api/v1/driver/me/ride requires ROLE_DRIVER and an eligible linked account.
The caller cannot select a driver, company or ride ID. The database query scopes
both the ride and driver to the authenticated driver's company and rechecks the
linked account, matching phone, verification and active statuses.

200 returns work details only: rideId, status, vehicleId, pickup and destination
addresses/coordinates, acceptedAt. No customer identity/contact or billing data.
204 with no body means no assigned active ride. Both responses use Cache-Control:
no-store. Terminal rides are excluded using RideStatus.ACTIVE_STATUSES; REQUESTED
rides normally have no driver and therefore do not match this lookup.

No state transitions, migrations, UI or native app changes are included.
The query expects at most one active ride per driver, enforced by the existing
schema invariant; it does not silently choose one if the invariant is violated.

Validation pending on the owner's Java 21 / Docker environment:

    .\mvnw.cmd -Dtest=DriverCurrentRideIntegrationTest test
    .\mvnw.cmd test

Eight integration tests cover own ride, minimal response, empty result, other
driver in the same company, another company, terminal states, active stages,
role restrictions and revoked binding (some scenarios share one test method).
No execution success is claimed until those commands finish successfully.
