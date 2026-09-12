package com.kalo.ride;

import com.jayway.jsonpath.JsonPath;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("The ride lifecycle from search to completion")
class RideLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    RideRepository rideRepository;

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "pickupAddress": "Rruga e Kavajes",
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300,
              "destinationAddress": "Sheshi Skenderbej"
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    @Test
    @DisplayName("a ride runs from request to completion and frees the driver")
    void happyPath() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        User customer = fixtures.customer();

        String customerToken = tokenFor(customer);
        String partnerToken = tokenFor(taxi.company().getOwner());

        long rideId = requestRide(customerToken);

        /* ------------------------------------------- accept and assign */

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverId":%d}
                                        """.formatted(taxi.driver().getId()))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRIVER_ASSIGNED"))
                .andExpect(jsonPath("$.acceptedAt").exists());

        assertThat(driverRepository.findById(taxi.driver().getId()).orElseThrow()
                .getAvailabilityStatus())
                .as("driver is taken off the market once assigned")
                .isEqualTo(DriverAvailabilityStatus.BUSY);

        /* ------------------------------------------------ drive it out */

        transition(partnerToken, rideId, "driver-arriving", "DRIVER_ARRIVING");
        transition(partnerToken, rideId, "driver-arrived", "DRIVER_ARRIVED");
        transition(partnerToken, rideId, "start", "IN_PROGRESS");

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/complete")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"finalAmount":1250.50}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.finalAmount").value(1250.50));

        /* ------------------------------------------------- after-effects */

        var ride = rideRepository.findById(rideId).orElseThrow();

        assertThat(ride.getStatus()).isEqualTo(RideStatus.COMPLETED);
        assertThat(ride.getFinalAmount()).isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(ride.getRequestedAt()).isNotNull();
        assertThat(ride.getAcceptedAt()).isNotNull();
        assertThat(ride.getDriverArrivingAt()).isNotNull();
        assertThat(ride.getDriverArrivedAt()).isNotNull();
        assertThat(ride.getStartedAt()).isNotNull();
        assertThat(ride.getCompletedAt()).isNotNull();

        assertThat(driverRepository.findById(taxi.driver().getId()).orElseThrow()
                .getAvailabilityStatus())
                .as("driver goes back on the market after the ride")
                .isEqualTo(DriverAvailabilityStatus.ONLINE);

        mockMvc.perform(
                        get("/api/v1/rides/history").header("Authorization", bearer(customerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("transitions cannot be skipped")
    void cannotSkipStates() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partnerToken = tokenFor(taxi.company().getOwner());

        long rideId = requestRide(tokenFor(fixtures.customer()));

        // Starting a ride nobody has accepted yet.
        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/start")
                                .header("Authorization", bearer(partnerToken))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a company cannot assign a driver that belongs to someone else")
    void cannotAssignAnotherCompanysDriver() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        TestDataFactory.BookableCompany rival = fixtures.bookableCompany();

        long rideId = requestRideFor(tokenFor(fixtures.customer()), taxi);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(tokenFor(taxi.company().getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverId":%d}
                                        """.formatted(rival.driver().getId()))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a company cannot touch another company's ride")
    void cannotDriveAnotherCompanysRide() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        TestDataFactory.BookableCompany rival = fixtures.bookableCompany();

        long rideId = requestRideFor(tokenFor(fixtures.customer()), taxi);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/decline")
                                .header("Authorization", bearer(tokenFor(rival.company().getOwner())))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a customer cannot open another customer's ride")
    void cannotReadAnotherCustomersRide() throws Exception {

        fixtures.bookableCompany();

        long rideId = requestRide(tokenFor(fixtures.customer()));
        String stranger = tokenFor(fixtures.customer());

        mockMvc.perform(
                        get("/api/v1/rides/" + rideId).header("Authorization", bearer(stranger))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("declining releases the request so another company can be chosen")
    void declineReturnsRequestToSearching() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        User customer = fixtures.customer();
        String customerToken = tokenFor(customer);

        long rideId = requestRide(customerToken);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/decline")
                                .header("Authorization", bearer(tokenFor(taxi.company().getOwner())))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        // The declined ride is terminal, so the customer is free to search again.
        mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a customer can cancel before the ride starts and the driver is released")
    void customerCancelsBeforeStart() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String customerToken = tokenFor(fixtures.customer());
        String partnerToken = tokenFor(taxi.company().getOwner());

        long rideId = requestRide(customerToken);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverId":%d}
                                        """.formatted(taxi.driver().getId()))
                )
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/rides/" + rideId + "/cancel")
                                .header("Authorization", bearer(customerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(driverRepository.findById(taxi.driver().getId()).orElseThrow()
                .getAvailabilityStatus())
                .as("a cancelled ride must not leave the driver stuck as BUSY")
                .isEqualTo(DriverAvailabilityStatus.ONLINE);
    }

    @Test
    @DisplayName("a customer cannot cancel a ride that is already under way")
    void cannotCancelInProgressRide() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String customerToken = tokenFor(fixtures.customer());
        String partnerToken = tokenFor(taxi.company().getOwner());

        long rideId = requestRide(customerToken);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverId":%d}
                                        """.formatted(taxi.driver().getId()))
                )
                .andExpect(status().isOk());

        transition(partnerToken, rideId, "driver-arriving", "DRIVER_ARRIVING");
        transition(partnerToken, rideId, "driver-arrived", "DRIVER_ARRIVED");
        transition(partnerToken, rideId, "start", "IN_PROGRESS");

        mockMvc.perform(
                        post("/api/v1/rides/" + rideId + "/cancel")
                                .header("Authorization", bearer(customerToken))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("completing requires a positive taximeter amount")
    void completeRejectsNonPositiveAmount() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String partnerToken = tokenFor(taxi.company().getOwner());

        long rideId = requestRide(tokenFor(fixtures.customer()));

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverId":%d}
                                        """.formatted(taxi.driver().getId()))
                )
                .andExpect(status().isOk());

        transition(partnerToken, rideId, "driver-arriving", "DRIVER_ARRIVING");
        transition(partnerToken, rideId, "driver-arrived", "DRIVER_ARRIVED");
        transition(partnerToken, rideId, "start", "IN_PROGRESS");

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/complete")
                                .header("Authorization", bearer(partnerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"finalAmount":0}
                                        """)
                )
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------------ helpers */

    private void transition(String partnerToken, long rideId, String path, String expected)
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/" + path)
                                .header("Authorization", bearer(partnerToken))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected));
    }

    /** Searches, then picks the first offer, and returns the new ride id. */
    private long requestRide(String customerToken) throws Exception {

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

        return selectOffer(customerToken, requestId, offerId);
    }

    /** Picks the offer belonging to a specific company. */
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

        // A filter expression returns every match, so take the one offer this
        // company made rather than assuming an ordering.
        List<Number> offerIds = JsonPath.read(
                search,
                "$.taxiOptions[?(@.companyId == %d)].offerId".formatted(taxi.company().getId())
        );

        assertThat(offerIds).as("the company under test was offered").hasSize(1);

        return selectOffer(customerToken, requestId, offerIds.get(0).longValue());
    }

    private long selectOffer(String customerToken, long requestId, long offerId) throws Exception {

        String ride = mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"offerId":%d}
                                        """.formatted(offerId))
                )
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(ride, "$.rideId")).longValue();
    }
}
