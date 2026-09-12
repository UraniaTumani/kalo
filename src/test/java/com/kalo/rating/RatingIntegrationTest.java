package com.kalo.rating;

import com.jayway.jsonpath.JsonPath;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Rating a completed ride")
class RatingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    TaxiCompanyRepository taxiCompanyRepository;

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
    @DisplayName("a completed ride can be rated and both averages move")
    void rateCompletedRide() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.driverRating").value(5))
                .andExpect(jsonPath("$.companyRating").value(4));

        assertThat(driverRepository.findById(scenario.driverId).orElseThrow().getRating())
                .isEqualTo(5.0);
        assertThat(driverRepository.findById(scenario.driverId).orElseThrow().getRatingCount())
                .isEqualTo(1);

        assertThat(taxiCompanyRepository.findById(scenario.companyId).orElseThrow().getRating())
                .isEqualTo(4.0);
        assertThat(taxiCompanyRepository.findById(scenario.companyId).orElseThrow().getRatingCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same ride cannot be rated twice")
    void cannotRateTwice() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().isConflict());

        // The second attempt must not double-count the average.
        assertThat(driverRepository.findById(scenario.driverId).orElseThrow().getRatingCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a ride that has not finished cannot be rated")
    void cannotRateUnfinishedRide() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        String customerToken = tokenFor(fixtures.customer());

        long rideId = requestRide(customerToken);

        mockMvc.perform(
                        post("/api/v1/rides/" + rideId + "/rating")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().is4xxClientError());

        assertThat(driverRepository.findById(taxi.driver().getId()).orElseThrow().getRatingCount())
                .isZero();
    }

    @Test
    @DisplayName("another passenger cannot rate someone else's ride")
    void otherCustomerCannotRate() throws Exception {

        Scenario scenario = completedRide();
        String stranger = tokenFor(fixtures.customer());

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(stranger))
                                .contentType(APPLICATION_JSON)
                                .content(RATING_BODY)
                )
                .andExpect(status().is4xxClientError());

        assertThat(driverRepository.findById(scenario.driverId).orElseThrow().getRatingCount())
                .isZero();
    }

    @Test
    @DisplayName("a rating outside one to five is rejected")
    void ratingMustBeWithinRange() throws Exception {

        Scenario scenario = completedRide();

        mockMvc.perform(
                        post("/api/v1/rides/" + scenario.rideId + "/rating")
                                .header("Authorization", bearer(scenario.customerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverRating":6,"companyRating":4}
                                        """)
                )
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------------ helpers */

    private record Scenario(long rideId, long driverId, long companyId, String customerToken) {
    }

    private Scenario completedRide() throws Exception {

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
                                        {"finalAmount":900.00}
                                        """)
                )
                .andExpect(status().isOk());

        return new Scenario(
                rideId,
                taxi.driver().getId(),
                taxi.company().getId(),
                customerToken
        );
    }

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
