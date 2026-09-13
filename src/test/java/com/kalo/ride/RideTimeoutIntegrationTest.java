package com.kalo.ride;

import com.jayway.jsonpath.JsonPath;
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

    /** Matches app.ride.company-response-timeout-seconds in the test profile. */
    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    RideTimeoutService rideTimeoutService;

    @Autowired
    RideRepository rideRepository;

    @Autowired
    RideRequestRepository rideRequestRepository;

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

    /** Reading the id off the proxy does not initialise it; touching anything else would. */
    private Long requestIdFor(long rideId) {
        return rideRepository.findById(rideId).orElseThrow().getRideRequest().getId();
    }
}
