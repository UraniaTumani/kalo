# Fleet mutation serialization

Partner driver writes, vehicle writes, assignment/unassignment and ride acceptance
acquire the same company row lock already used by driver self availability.
This deliberately serializes these writes per company, not across all companies.
It trades some per-company write throughput for a clear first implementation.
Search remains a non-locking snapshot; acceptance rechecks eligibility.

Order: ride (when accepting), company, driver. Fleet writers do not acquire rides.
Driver self GPS/availability and ride actions already lock company before driver.
The company entity is refreshed after waiting before checking active/approved.
Vehicle deactivation and unassignment refresh and lock the driver before reading
BUSY/OFFLINE, avoiding stale JPA state and serializing with completion/sweeps.

If deactivation wins, later ONLINE/accept sees the inactive vehicle/released
assignment and refuses. If acceptance wins, deactivation sees BUSY and refuses.
If ONLINE wins, a later vehicle deactivation may take the driver offline and
release the assignment as before. Unassignment refuses an ONLINE/BUSY driver.

Existing database uniqueness constraints remain in force. No migration, payment
or production data changes. Out-of-band SQL is not covered by the service lock.
Company administration/document review and all future writers must be audited
before extending this guarantee beyond these entry points. No lock timeouts,
automatic mutation retries or idempotency protocol are introduced here.

Validation on owner environment:

    .\mvnw.cmd -Dtest=FleetMutationConcurrencyIntegrationTest,AssignmentIntegrationTest,DriverSelfAvailabilityIntegrationTest,RideLifecycleIntegrationTest test
    .\mvnw.cmd test

Then run frontend/e2e/run-invitation-integration.mjs from frontend. No execution
result is claimed by this document before those commands complete.
