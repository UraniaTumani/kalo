package com.kalo.ride;

import com.jayway.jsonpath.JsonPath;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.entity.RideRequest;
import com.kalo.ride.enums.RideRequestStatus;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.repository.RideRequestRepository;
import com.kalo.ride.service.RideTimeoutService;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one piece of ride logic that runs without anybody asking.
 *
 * Everything else in the lifecycle happens because a customer or a partner
 * pressed something, so a bug shows up as a failed request. This runs on a
 * timer: when it misbehaves, a ride quietly sits in the wrong state and the
 * first person to find out is the passenger still waiting.
 *
 * The scheduler is off in the test suite, so each test runs exactly one sweep at
 * a moment it chooses rather than racing a five-second timer.
 */
@DisplayName("The ride timeout sweep")
class RideTimeoutIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "pickupAddress": "Rruga e Kavajes",
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300,
              "destinationAddress": "Sheshi Skenderbej"
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    /**
     * Matches app.ride.company-response-timeout-seconds in the TEST profile.
     *
     * Production ships 120 (F33); this stays 60 because the suite ages rides past
     * a known cutoff rather than waiting, and the two profiles are decoupled so
     * the production default can move without touching these tests. The shipped
     * value is pinned separately by ProductionConfigurationTest, because nothing
     * here would notice if it drifted back to a minute.
     */
    private static final long TIMEOUT_SECONDS = 60;

    /**
     * How many times the accept-versus-sweep race is run.
     *
     * More than one because the interleaving is genuinely non-deterministic: a
     * single attempt that happened to resolve one way would pass without ever
     * exercising the other.
     */
    private static final int RACE_ATTEMPTS = 6;

    @Autowired
    RideTimeoutService rideTimeoutService;

    @Autowired
    RideRepository rideRepository;

    @Autowired
    RideRequestRepository rideRequestRepository;

    @Autowired
    DriverRepository driverRepository;

    @Test
    @DisplayName("a company that never answers loses the ride to NO_RESPONSE")
    void unansweredRideTimesOut() {

        long rideId = requestRide();

        ageRide(rideId, TIMEOUT_SECONDS + 5);

        rideTimeoutService.processTimedOutRides();

        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.NO_RESPONSE);
    }

    @Test
    @DisplayName("the request goes back to SEARCHING so the customer can pick another company")
    void requestReturnsToSearching() {

        long rideId = requestRide();

        ageRide(rideId, TIMEOUT_SECONDS + 5);

        rideTimeoutService.processTimedOutRides();

        /*
         * The point of the whole mechanism: a silent company must not strand the
         * customer. The request has to become bookable again.
         */
        assertThat(requestStatusFor(rideId)).isEqualTo(RideRequestStatus.SEARCHING);
    }

    @Test
    @DisplayName("a request that has itself expired ends as EXPIRED, not SEARCHING")
    void expiredRequestDoesNotReturnToSearching() {

        long rideId = requestRide();

        ageRide(rideId, TIMEOUT_SECONDS + 5);
        expireRequestFor(rideId);

        rideTimeoutService.processTimedOutRides();

        // Nothing to go back to: re-opening a search the customer has already
        // walked away from would offer them a ride they never asked for twice.
        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.NO_RESPONSE);
        assertThat(requestStatusFor(rideId)).isEqualTo(RideRequestStatus.EXPIRED);
    }

    @Test
    @DisplayName("a ride still inside the window is left alone")
    void rideInsideTheWindowIsUntouched() {

        long rideId = requestRide();

        // Old, but not old enough.
        ageRide(rideId, TIMEOUT_SECONDS - 20);

        rideTimeoutService.processTimedOutRides();

        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.REQUESTED);
    }

    @Test
    @DisplayName("a ride the company accepted in time is left alone")
    void acceptedRideIsUntouched() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        long rideId = requestRideFor(taxi);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header(
                                        "Authorization",
                                        bearer(tokenFor(taxi.company().getOwner()))
                                )
                                .contentType(APPLICATION_JSON)
                                .content("{\"driverId\":%d}".formatted(taxi.driver().getId()))
                )
                .andExpect(status().isOk());

        /*
         * Accepted, then aged past the cutoff. The sweep re-reads the status
         * after taking the lock precisely so a ride accepted at the last moment
         * is not cancelled out from under the driver.
         */
        ageRide(rideId, TIMEOUT_SECONDS + 5);

        rideTimeoutService.processTimedOutRides();

        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.DRIVER_ASSIGNED);

        /*
         * The whole consistent triple, not just the ride row.
         *
         * This is the accept-wins state that the race test below also checks, and
         * asserting it here is what stops that check from being hollow: if the
         * race happens to resolve in the sweep's favour every time, its
         * accept-wins assertions never execute, while these always do.
         */
        assertThat(availabilityOf(taxi.driver().getId()))
                .as("an assigned driver must be BUSY")
                .isEqualTo(DriverAvailabilityStatus.BUSY);

        assertThat(requestStatusFor(rideId))
                .as("a driver is assigned, so the customer must not be sent back to searching")
                .isNotEqualTo(RideRequestStatus.SEARCHING);
    }

    /**
     * The other ordering, forced rather than raced.
     *
     * Counterpart to acceptedRideIsUntouched: together they pin both consistent
     * outcomes deterministically, so the race test is left to do the one job
     * neither of them can — catching an interleaving that produces a state
     * belonging to neither.
     */
    @Test
    @DisplayName("a ride the sweep has taken can no longer be accepted")
    void sweptOutRideCannotThenBeAccepted() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        long rideId = requestRideFor(taxi);

        ageRide(rideId, TIMEOUT_SECONDS + 5);

        rideTimeoutService.processTimedOutRides();

        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.NO_RESPONSE);

        int acceptStatus = mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header(
                                        "Authorization",
                                        bearer(tokenFor(taxi.company().getOwner()))
                                )
                                .contentType(APPLICATION_JSON)
                                .content("{\"driverId\":%d}".formatted(taxi.driver().getId()))
                )
                .andReturn().getResponse().getStatus();

        assertThat(acceptStatus)
                .as("the ride is no longer REQUESTED, so accepting it must be refused")
                .isNotEqualTo(200);

        /*
         * The refusal has to leave everything as the sweep left it. A rejected
         * accept that still marked the driver BUSY would strand them on a ride
         * that no longer exists, and nothing on any screen would say why the
         * company had stopped appearing in search.
         */
        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.NO_RESPONSE);

        assertThat(availabilityOf(taxi.driver().getId()))
                .as("a refused accept must not stand the driver down as BUSY")
                .isEqualTo(DriverAvailabilityStatus.ONLINE);

        assertThat(requestStatusFor(rideId))
                .as("the request must still be bookable elsewhere")
                .isEqualTo(RideRequestStatus.SEARCHING);
    }

    @Test
    @DisplayName("one sweep clears every ride that is due")
    void sweepHandlesMoreThanOneRide() {

        long first = requestRide();
        long second = requestRide();

        ageRide(first, TIMEOUT_SECONDS + 5);
        ageRide(second, TIMEOUT_SECONDS + 5);

        rideTimeoutService.processTimedOutRides();

        assertThat(rideStatus(first)).isEqualTo(RideStatus.NO_RESPONSE);
        assertThat(rideStatus(second)).isEqualTo(RideStatus.NO_RESPONSE);
    }

    @Test
    @DisplayName("a second sweep changes nothing")
    void sweepIsIdempotent() {

        long rideId = requestRide();

        ageRide(rideId, TIMEOUT_SECONDS + 5);

        rideTimeoutService.processTimedOutRides();
        rideTimeoutService.processTimedOutRides();

        // The sweep runs every five seconds forever; it has to be safe to repeat.
        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.NO_RESPONSE);
        assertThat(requestStatusFor(rideId)).isEqualTo(RideRequestStatus.SEARCHING);
    }

    @Test
    @DisplayName("a sweep with nothing due is harmless")
    void sweepWithNothingToDo() {

        long rideId = requestRide();

        rideTimeoutService.processTimedOutRides();

        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.REQUESTED);
    }

    /**
     * The race itself, rather than the two orderings separately.
     *
     * acceptedRideIsUntouched above proves the accept-then-sweep sequence, which
     * is the easy half: the sweep re-reads the status after taking the lock and
     * walks away. This runs the two genuinely at once, because the dangerous
     * outcome is not either side losing — it is the pair of them half-winning and
     * leaving a state no screen can represent.
     *
     * Two inconsistencies are what this is really watching for, and both would be
     * silent in production:
     *
     *   NO_RESPONSE with a BUSY driver — the ride is dead but the driver is stood
     *   down, so the company quietly drops out of search with nobody to tell.
     *
     *   DRIVER_ASSIGNED with the request back to SEARCHING — a driver is on the
     *   way while the customer is being invited to book somebody else.
     *
     * Which side wins is not asserted. It is a real race, either split is legal,
     * and pinning a ratio would make this flaky for no gain. What is asserted is
     * that whichever wins, every related row agrees with it.
     *
     * Raising the window from 60 to 120 does not touch this mechanism — the
     * cutoff moves, the locking does not — so this test is written to hold at any
     * timeout value.
     */
    @Test
    @DisplayName("a company accepting while the sweep runs cannot leave a conflicting state")
    void acceptRacingTheSweepLeavesOneConsistentState() throws Exception {

        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {

            int acceptWon = 0;
            int sweepWon = 0;

            for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {

                TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

                long rideId = requestRideFor(taxi);
                long driverId = taxi.driver().getId();

                /* Due the instant the sweep looks at it. */
                ageRide(rideId, TIMEOUT_SECONDS + 5);

                CountDownLatch go = new CountDownLatch(1);

                Future<?> sweep = pool.submit(() -> {
                    go.await();
                    rideTimeoutService.processTimedOutRides();
                    return null;
                });

                /*
                 * MockMvc is driven only from this thread. The sweep is a plain
                 * service call and is safe to run from another; doing it the
                 * other way round would hand a request off the main thread for
                 * no reason.
                 */
                go.countDown();

                int acceptStatus = mockMvc.perform(
                                post("/api/v1/partner/rides/" + rideId + "/accept")
                                        .header(
                                                "Authorization",
                                                bearer(tokenFor(taxi.company().getOwner()))
                                        )
                                        .contentType(APPLICATION_JSON)
                                        .content("{\"driverId\":%d}".formatted(driverId))
                        )
                        .andReturn().getResponse().getStatus();

                sweep.get(30, TimeUnit.SECONDS);

                RideStatus status = rideStatus(rideId);
                RideRequestStatus request = requestStatusFor(rideId);
                DriverAvailabilityStatus availability = availabilityOf(driverId);

                if (status == RideStatus.DRIVER_ASSIGNED) {

                    acceptWon++;

                    assertThat(acceptStatus)
                            .as("attempt %d: the ride is assigned, so the accept must have succeeded", attempt)
                            .isEqualTo(200);

                    assertThat(availability)
                            .as("attempt %d: an assigned driver must be BUSY", attempt)
                            .isEqualTo(DriverAvailabilityStatus.BUSY);

                    assertThat(request)
                            .as("attempt %d: a driver is assigned, so the customer must not be "
                                    + "sent back to searching", attempt)
                            .isNotEqualTo(RideRequestStatus.SEARCHING);

                } else {

                    sweepWon++;

                    assertThat(status)
                            .as("attempt %d: the only other legal outcome is NO_RESPONSE", attempt)
                            .isEqualTo(RideStatus.NO_RESPONSE);

                    assertThat(acceptStatus)
                            .as("attempt %d: the sweep took the ride, so the accept must have been refused", attempt)
                            .isNotEqualTo(200);

                    assertThat(availability)
                            .as("attempt %d: a timed-out ride must not leave its driver stood down as BUSY", attempt)
                            .isEqualTo(DriverAvailabilityStatus.ONLINE);

                    assertThat(request)
                            .as("attempt %d: a timed-out request must be bookable again", attempt)
                            .isEqualTo(RideRequestStatus.SEARCHING);
                }
            }

            assertThat(acceptWon + sweepWon)
                    .as("every attempt must have resolved to one side or the other")
                    .isEqualTo(RACE_ATTEMPTS);

        } finally {
            pool.shutdownNow();
        }
    }

    /* ----------------------------------------------------------- helpers */

    private long requestRide() {
        return requestRideFor(fixtures.bookableCompany());
    }

    /** Books through the real search-and-select flow, as a customer would. */
    private long requestRideFor(TestDataFactory.BookableCompany taxi) {

        try {

            User customer = fixtures.customer();
            String token = tokenFor(customer);

            String search = mockMvc.perform(
                            post("/api/v1/rides/search")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content(SEARCH_BODY)
                    )
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            long requestId = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();
            long offerId = ((Number) JsonPath.read(search, "$.taxiOptions[0].offerId")).longValue();

            String selected = mockMvc.perform(
                            post("/api/v1/rides/requests/" + requestId + "/select")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content("{\"offerId\":%d}".formatted(offerId))
                    )
                    .andExpect(status().is2xxSuccessful())
                    .andReturn().getResponse().getContentAsString();

            return ((Number) JsonPath.read(selected, "$.rideId")).longValue();

        } catch (Exception exception) {
            throw new IllegalStateException("Could not book a ride for the test", exception);
        }
    }

    /**
     * Moves the ride's clock backwards instead of making the test wait a minute.
     * The sweep reads requestedAt, so this is the honest equivalent of time
     * passing.
     */
    private void ageRide(long rideId, long seconds) {

        Ride ride = rideRepository.findById(rideId).orElseThrow();
        ride.setRequestedAt(Instant.now().minus(Duration.ofSeconds(seconds)));
        rideRepository.save(ride);
    }

    private void expireRequestFor(long rideId) {

        // Loaded through its own repository: the ride holds a lazy proxy, and
        // there is no session open out here to initialise it.
        RideRequest request = rideRequestRepository
                .findById(requestIdFor(rideId))
                .orElseThrow();

        request.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        rideRequestRepository.save(request);
    }

    private RideStatus rideStatus(long rideId) {
        return rideRepository.findById(rideId).orElseThrow().getStatus();
    }

    private RideRequestStatus requestStatusFor(long rideId) {
        return rideRequestRepository.findById(requestIdFor(rideId)).orElseThrow().getStatus();
    }

    /** Read back from the database, not from the fixture's stale copy. */
    private DriverAvailabilityStatus availabilityOf(long driverId) {
        return driverRepository.findById(driverId).orElseThrow().getAvailabilityStatus();
    }

    /** Reading the id off the proxy does not initialise it; touching anything else would. */
    private Long requestIdFor(long rideId) {
        return rideRepository.findById(rideId).orElseThrow().getRideRequest().getId();
    }
}
