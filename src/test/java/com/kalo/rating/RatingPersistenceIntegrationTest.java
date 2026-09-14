package com.kalo.rating;

import com.kalo.notification.repository.CompanyNotificationRepository;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a rating leaves behind once the passenger closes the app.
 *
 * RatingIntegrationTest already covered that a rating is written and that the
 * two aggregates move. What nothing covered was whether anyone can *see* it
 * afterwards: the ride API returned no rating state at all, so history offered
 * a Rate button on every completed ride forever, and a passenger only learned
 * they had already rated by filling the form in and having it refused. The
 * screen then reported that refusal as success.
 *
 * These tests are about the state that survives a reload.
 */
@DisplayName("A rating that outlives the moment")
class RatingPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CompanyNotificationRepository notificationRepository;

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    private static final String RATING_BODY = """
            {"driverRating":5,"companyRating":4,"comment":"Quick and friendly"}
            """;

    @Test
    @DisplayName("history says a rated ride is rated, and carries the score back")
    void historyReportsTheRating() throws Exception {

        Scenario scenario = completedRide();

        /* Before rating, history offers it as unrated. */
        mockMvc.perform(
                        get("/api/v1/rides/history")
                                .header("Authorization", bearer(scenario.customerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rated").value(false))
                .andExpect(jsonPath("$.content[0].driverRating").doesNotExist());

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isCreated());

        /*
         * This is the assertion the bug was hiding behind. A fresh read, as a
         * reload would make, has to come back saying the ride is rated.
         */
        mockMvc.perform(
                        get("/api/v1/rides/history")
                                .header("Authorization", bearer(scenario.customerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rated").value(true))
                .andExpect(jsonPath("$.content[0].driverRating").value(5))
                .andExpect(jsonPath("$.content[0].companyRating").value(4));
    }

    @Test
    @DisplayName("reading the ride on its own says the same thing")
    void rideByIdReportsTheRating() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/rides/" + scenario.rideId)
                                .header("Authorization", bearer(scenario.customerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rated").value(true))
                .andExpect(jsonPath("$.driverRating").value(5));
    }

    @Test
    @DisplayName("the rating is in PostgreSQL, not just in the response")
    void ratingIsPersisted() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isCreated());

        /* Read straight out of the table, past every layer of Java. */
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT driver_rating FROM ride_ratings WHERE ride_id = ?",
                Integer.class,
                scenario.rideId
        );

        assertThat(stored).isEqualTo(5);

        String comment = jdbcTemplate.queryForObject(
                "SELECT comment FROM ride_ratings WHERE ride_id = ?",
                String.class,
                scenario.rideId
        );

        assertThat(comment).isEqualTo("Quick and friendly");
    }

    @Test
    @DisplayName("a second rating is refused and does not overwrite the first")
    void duplicateIsRefused() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverRating":1,"companyRating":1,"comment":"Changed my mind"}
                                        """)
                )
                .andExpect(status().isConflict());

        /*
         * The refusal has to leave the original alone. A duplicate that came
         * back 409 but had already written would be worse than one that
         * succeeded.
         */
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT driver_rating FROM ride_ratings WHERE ride_id = ?",
                Integer.class,
                scenario.rideId
        );

        assertThat(stored).isEqualTo(5);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ride_ratings WHERE ride_id = ?",
                Integer.class,
                scenario.rideId
        );

        assertThat(rows).isEqualTo(1);

        /* And history still shows the first rating, not the rejected one. */
        mockMvc.perform(
                        get("/api/v1/rides/history")
                                .header("Authorization", bearer(scenario.customerToken))
                )
                .andExpect(jsonPath("$.content[0].driverRating").value(5));
    }

    @Test
    @DisplayName("the company is told, and the notification is in the database")
    void companyIsNotified() throws Exception {

        Scenario scenario = completedRide();

        assertThat(notificationRepository.countByCompanyIdAndReadAtIsNull(scenario.companyId))
                .as("nothing to tell them before the rating")
                .isZero();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isCreated());

        Integer rows = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM company_notifications
                WHERE company_id = ? AND type = 'RIDE_RATED' AND ride_id = ?
                """,
                Integer.class,
                scenario.companyId,
                scenario.rideId
        );

        assertThat(rows).as("the notification must be a row, not an event").isEqualTo(1);

        assertThat(notificationRepository.countByCompanyIdAndReadAtIsNull(scenario.companyId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the partner reads the notification through their own endpoint")
    void partnerSeesTheNotification() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isCreated());

        String body = mockMvc.perform(
                        get("/api/v1/partner/notifications")
                                .header("Authorization", bearer(scenario.partnerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("RIDE_RATED"))
                .andExpect(jsonPath("$.content[0].rideId").value(scenario.rideId))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(
                        get("/api/v1/partner/notifications/unread-count")
                                .header("Authorization", bearer(scenario.partnerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));

        long notificationId =
                ((Number) JsonPath.read(body, "$.content[0].id")).longValue();

        mockMvc.perform(
                        post("/api/v1/partner/notifications/" + notificationId + "/read")
                                .header("Authorization", bearer(scenario.partnerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(
                        get("/api/v1/partner/notifications/unread-count")
                                .header("Authorization", bearer(scenario.partnerToken))
                )
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("one company never sees another company's notifications")
    void notificationsAreScopedToTheCompany() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isCreated());

        var otherCompany = fixtures.approvedCompany();

        mockMvc.perform(
                        get("/api/v1/partner/notifications")
                                .header("Authorization", bearer(tokenFor(otherCompany.getOwner())))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a customer cannot read a company's notifications")
    void customersAreRefused() throws Exception {

        mockMvc.perform(
                        get("/api/v1/partner/notifications")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                )
                .andExpect(status().isForbidden());
    }

    /* ----------------------------------------------------------- fixture */

    private record Scenario(
            long rideId, long companyId,
            String customerToken, String partnerToken
    ) {
    }

    /**
     * Drives a ride all the way to COMPLETED through the real endpoints, which
     * is the only state a rating is allowed from.
     */
    private Scenario completedRide() throws Exception {

        var bookable = fixtures.bookableCompany();
        var customer = fixtures.customer();

        String customerToken = tokenFor(customer);
        String partnerToken = tokenFor(bookable.company().getOwner());

        String search = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long requestId = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();
        long offerId = ((Number) JsonPath.read(search, "$.taxiOptions[0].offerId")).longValue();

        String selected = mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content("{\"offerId\":" + offerId + "}")
                )
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        long rideId = ((Number) JsonPath.read(selected, "$.rideId")).longValue();

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverId":%d}
                                        """.formatted(bookable.driver().getId()))
                )
                .andExpect(status().isOk());

        for (String step : new String[]{"driver-arriving", "driver-arrived", "start"}) {
            mockMvc.perform(
                            post("/api/v1/partner/rides/" + rideId + "/" + step)
                                    .header("Authorization", bearer(partnerToken))
                    )
                    .andExpect(status().isOk());
        }

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/complete")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"finalAmount":850.00}
                                        """)
                )
                .andExpect(status().isOk());

        return new Scenario(
                rideId,
                bookable.company().getId(),
                customerToken,
                partnerToken
        );
    }
}
