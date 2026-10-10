package com.kalo.ride;

import com.jayway.jsonpath.JsonPath;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.service.RideTimeoutService;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the dispatcher's queue returns, and what it leaves out (F41).
 *
 * The queue could previously be narrowed to one status or not at all. Neither
 * is the view a dispatcher works from: ALL buries the handful of rides they can
 * act on under every ride the company has ever taken — one actionable row among
 * thirty-four on the development database — while REQUESTED alone hides a ride
 * the instant it is accepted, which is precisely when they start needing it,
 * since arriving, arrived, started and completed are all driven from this same
 * screen.
 *
 * `active=true` answers with everything still in flight. The five statuses that
 * means are {@link RideStatus#ACTIVE_STATUSES} and are never restated here: a
 * test that listed them itself would pass while the queue and the enum drifted
 * apart, which is the one failure this file exists to prevent.
 *
 * TWO DATABASE CONSTRAINTS SHAPE EVERY SETUP BELOW, and they are the reason
 * these tests look more elaborate than the behaviour they check. Migration 017
 * allows one active ride per customer and one per driver, so five simultaneous
 * active rides need five customers and four free drivers — a REQUESTED ride has
 * no driver yet. Reusing either would fail on a unique index rather than on the
 * assertion.
 */
@DisplayName("The partner ride queue")
class PartnerRideQueueIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "pickupAddress": "Rruga e Kavajes",
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300,
              "destinationAddress": "Sheshi Skenderbej"
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    @Autowired
    RideRepository rideRepository;

    @Autowired
    RideTimeoutService rideTimeoutService;

    /* ------------------------------------------------------------- active */

    @Test
    @DisplayName("every active status is returned")
    void allActiveStatusesAreReturned() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        /* One ride per active status, each on its own customer and driver. */
        long requested = requestRideFrom(taxi);

        long assigned = acceptNewRide(taxi, partner);

        long arriving = acceptNewRide(taxi, partner);
        transition(partner, arriving, "driver-arriving");

        long arrived = acceptNewRide(taxi, partner);
        transition(partner, arrived, "driver-arriving");
        transition(partner, arrived, "driver-arrived");

        long inProgress = acceptNewRide(taxi, partner);
        transition(partner, inProgress, "driver-arriving");
        transition(partner, inProgress, "driver-arrived");
        transition(partner, inProgress, "start");

        assertThat(statusesIn(queue(partner, "?active=true&size=50")))
                .as("the queue's idea of active must be the enum's, exactly")
                .containsExactlyInAnyOrderElementsOf(RideStatus.ACTIVE_STATUSES);

        assertThat(idsIn(queue(partner, "?active=true&size=50")))
                .containsExactlyInAnyOrder(requested, assigned, arriving, arrived, inProgress);
    }

    @Test
    @DisplayName("terminal statuses are left out")
    void terminalStatusesAreExcluded() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        long completed = acceptNewRide(taxi, partner);
        transition(partner, completed, "driver-arriving");
        transition(partner, completed, "driver-arrived");
        transition(partner, completed, "start");
        complete(partner, completed);

        /* Cancelled by its customer before it started. */
        User canceller = fixtures.customer();
        long cancelled = requestRideFor(tokenFor(canceller), taxi);
        mockMvc.perform(
                        post("/api/v1/rides/" + cancelled + "/cancel")
                                .header("Authorization", bearer(tokenFor(canceller)))
                )
                .andExpect(status().isOk());

        /* Declined by the company. */
        long declined = requestRideFrom(taxi);
        mockMvc.perform(
                        post("/api/v1/partner/rides/" + declined + "/decline")
                                .header("Authorization", bearer(partner))
                )
                .andExpect(status().isOk());

        /* Never answered, then swept. */
        long noResponse = requestRideFrom(taxi);
        ageRide(noResponse);
        rideTimeoutService.processTimedOutRides();

        assertThat(statusesIn(queue(partner, "?active=true&size=50")))
                .as("nothing terminal belongs in a queue of work in hand")
                .isEmpty();

        assertThat(idsIn(queue(partner, "?size=50")))
                .as("but all four are still the company's rides")
                .contains(completed, cancelled, declined, noResponse);
    }

    @Test
    @DisplayName("an accepted ride stays visible for the rest of its lifecycle")
    void acceptedRideStaysVisible() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        long rideId = requestRideFrom(taxi);

        assertThat(idsIn(queue(partner, "?active=true&size=50")))
                .as("REQUESTED, before anyone has touched it")
                .contains(rideId);

        accept(partner, rideId, onlineDriverOf(partner));

        /*
         * The whole point of the finding. Under a REQUESTED-only default the
         * ride would have vanished here, at the moment the dispatcher became
         * responsible for driving it.
         */
        assertThat(idsIn(queue(partner, "?active=true&size=50")))
                .as("DRIVER_ASSIGNED — the step a REQUESTED filter would hide")
                .contains(rideId);

        for (String step : List.of("driver-arriving", "driver-arrived", "start")) {

            transition(partner, rideId, step);

            assertThat(idsIn(queue(partner, "?active=true&size=50")))
                    .as("still in hand after %s", step)
                    .contains(rideId);
        }

        complete(partner, rideId);

        assertThat(idsIn(queue(partner, "?active=true&size=50")))
                .as("and gone once it is finished")
                .doesNotContain(rideId);

        assertThat(idsIn(queue(partner, "?size=50")))
                .as("without disappearing from the company's history")
                .contains(rideId);
    }

    /* ------------------------------------------------- parameter contract */

    @Test
    @DisplayName("active=false means the same as not asking")
    void activeFalseIsUnfiltered() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        long active = requestRideFrom(taxi);

        long finished = acceptNewRide(taxi, partner);
        transition(partner, finished, "driver-arriving");
        transition(partner, finished, "driver-arrived");
        transition(partner, finished, "start");
        complete(partner, finished);

        /*
         * Only TRUE switches the filter on, so false has to behave as absence
         * rather than as "show me nothing" or "show me terminal rides".
         */
        assertThat(idsIn(queue(partner, "?active=false&size=50")))
                .containsExactlyInAnyOrderElementsOf(idsIn(queue(partner, "?size=50")))
                .contains(active, finished);
    }

    @Test
    @DisplayName("active=false still lets a status filter through")
    void activeFalseLeavesStatusAlone() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        long requested = requestRideFrom(taxi);
        long assigned = acceptNewRide(taxi, partner);

        assertThat(idsIn(queue(partner, "?active=false&status=REQUESTED&size=50")))
                .as("false is not a filter, so the status is the only one applied")
                .contains(requested)
                .doesNotContain(assigned);
    }

    @Test
    @DisplayName("asking for active and a status at once is refused")
    void activeWithStatusIsRejected() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        /*
         * Refused rather than resolved by precedence. Guessing which half the
         * caller meant would hide the bug that produced the request.
         */
        mockMvc.perform(
                        get("/api/v1/partner/rides?active=true&status=REQUESTED")
                                .header("Authorization", bearer(partner))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Ask for either a single status or active=true, not both"
                ));
    }

    @Test
    @DisplayName("the existing single-status filter is unchanged")
    void statusFilterStillWorks() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        long requested = requestRideFrom(taxi);
        long assigned = acceptNewRide(taxi, partner);

        assertThat(idsIn(queue(partner, "?status=REQUESTED&size=50")))
                .containsExactly(requested);

        assertThat(idsIn(queue(partner, "?status=DRIVER_ASSIGNED&size=50")))
                .containsExactly(assigned);
    }

    /* ----------------------------------------- scoping, order, pagination */

    @Test
    @DisplayName("one company's active rides are invisible to another")
    void companyScopingHolds() throws Exception {

        TestDataFactory.BookableCompany mine = fixtures.bookableCompany();
        TestDataFactory.BookableCompany theirs = fixtures.bookableCompany();

        long myRide = requestRideFrom(mine);
        long theirRide = requestRideFrom(theirs);

        assertThat(idsIn(queue(tokenFor(mine.company().getOwner()), "?active=true&size=50")))
                .contains(myRide)
                .doesNotContain(theirRide);

        assertThat(idsIn(queue(tokenFor(theirs.company().getOwner()), "?active=true&size=50")))
                .contains(theirRide)
                .doesNotContain(myRide);
    }

    @Test
    @DisplayName("newest first, as the unfiltered queue already was")
    void orderingIsNewestFirst() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        long first = requestRideFrom(taxi);
        long second = requestRideFrom(taxi);
        long third = requestRideFrom(taxi);

        /*
         * Ordering was verified end to end during the Section 25 audit and the
         * controller's @PageableDefault is deliberately untouched by F41. This
         * is here so a later change to the active query cannot quietly drop it.
         */
        assertThat(idsIn(queue(partner, "?active=true&size=50")))
                .containsExactly(third, second, first);
    }

    @Test
    @DisplayName("pagination applies to the active queue")
    void paginationApplies() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partner = tokenFor(taxi.company().getOwner());

        long first = requestRideFrom(taxi);
        long second = requestRideFrom(taxi);
        long third = requestRideFrom(taxi);

        String page0 = queue(partner, "?active=true&size=2&page=0");

        assertThat(idsIn(page0)).containsExactly(third, second);
        assertThat(((Number) JsonPath.read(page0, "$.totalElements")).intValue())
                .as("the total counts the active rides, not the page")
                .isEqualTo(3);

        assertThat(idsIn(queue(partner, "?active=true&size=2&page=1")))
                .containsExactly(first);
    }

    /* ----------------------------------------------------------- helpers */

    private String queue(String partnerToken, String query) throws Exception {

        return mockMvc.perform(
                        get("/api/v1/partner/rides" + query)
                                .header("Authorization", bearer(partnerToken))
                )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<Long> idsIn(String queueResponse) {

        List<Number> raw = JsonPath.read(queueResponse, "$.content[*].rideId");

        return raw.stream().map(Number::longValue).toList();
    }

    private List<RideStatus> statusesIn(String queueResponse) {

        List<String> raw = JsonPath.read(queueResponse, "$.content[*].status");

        return raw.stream().map(RideStatus::valueOf).toList();
    }

    /** A fresh customer books this company; the ride stays REQUESTED. */
    private long requestRideFrom(TestDataFactory.BookableCompany taxi) throws Exception {
        return requestRideFor(tokenFor(fixtures.customer()), taxi);
    }

    /**
     * A fresh customer books, a fresh driver takes it.
     *
     * A new driver each time because one active ride per driver is a unique
     * index, not a convention.
     */
    private long acceptNewRide(TestDataFactory.BookableCompany taxi, String partnerToken)
            throws Exception {

        Driver driver = fixtures.driver(
                taxi.company(),
                DriverStatus.ACTIVE,
                DriverAvailabilityStatus.ONLINE
        );
        fixtures.assign(driver, fixtures.vehicle(taxi.company(), VehicleStatus.ACTIVE));
        fixtures.freshLocation(driver);

        long rideId = requestRideFrom(taxi);

        accept(partnerToken, rideId, driver.getId());

        return rideId;
    }

    private void accept(String partnerToken, long rideId, long driverId) throws Exception {

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("{\"driverId\":%d}".formatted(driverId))
                )
                .andExpect(status().isOk());
    }

    private long onlineDriverOf(String partnerToken) throws Exception {

        String drivers = mockMvc.perform(
                        get("/api/v1/partner/drivers?availabilityStatus=ONLINE&size=50")
                                .header("Authorization", bearer(partnerToken))
                )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(drivers, "$.content[0].id")).longValue();
    }

    private void transition(String partnerToken, long rideId, String path) throws Exception {

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/" + path)
                                .header("Authorization", bearer(partnerToken))
                )
                .andExpect(status().isOk());
    }

    private void complete(String partnerToken, long rideId) throws Exception {

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/complete")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("{\"finalAmount\":850.0}")
                )
                .andExpect(status().isOk());
    }

    /** Moves a ride's clock back so one sweep will time it out. */
    private void ageRide(long rideId) {

        Ride ride = rideRepository.findById(rideId).orElseThrow();
        ride.setRequestedAt(Instant.now().minus(Duration.ofMinutes(10)));
        rideRepository.save(ride);
    }

    /** Picks the offer this company made, rather than assuming an ordering. */
    private long requestRideFor(String customerToken, TestDataFactory.BookableCompany taxi)
            throws Exception {

        String search = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long requestId = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();

        List<Number> offerIds = JsonPath.read(
                search,
                "$.taxiOptions[?(@.companyId == %d)].offerId".formatted(taxi.company().getId())
        );

        assertThat(offerIds).as("the company under test was offered").hasSize(1);

        String ride = mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content("{\"offerId\":%d}".formatted(offerIds.get(0).longValue()))
                )
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(ride, "$.rideId")).longValue();
    }
}
