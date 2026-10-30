# Operational vehicle eligibility

ONLINE through partner or driver APIs and ride acceptance now require an active
vehicle of the same company with registration, insurance and technical inspection
expiry dates present and valid through today in Europe/Tirane. Search and dashboard
use matching predicates. OFFLINE retains existing authorization and BUSY rules.

These fields are operational declarations, not proof that uploaded documents are
approved. Passenger insurance, taximeter verification and document review remain
separate work. No migrations or existing data changes are included.

This patch adds validation at request time. It does not resolve all concurrent
vehicle edits or assignment changes; the assignment/vehicle writers need a shared
locking protocol before claiming that guarantee. It also does not force existing
ONLINE drivers offline or change completion's existing return-to-ONLINE behavior.
Search and acceptance independently reject vehicles whose dates are invalid.

Validation must run on the owner environment:

    .\mvnw.cmd -Dtest=VehicleEligibilityTest,DriverSelfAvailabilityIntegrationTest,RideSearchIntegrationTest,RideLifecycleIntegrationTest test
    .\mvnw.cmd test

The integration browser scenario should also be rerun because it creates vehicles
and goes online. Tests have not been executed in the patch author's environment.
