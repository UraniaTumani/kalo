package com.kalo.ride;

import com.jayway.jsonpath.JsonPath;
import com.kalo.notification.repository.CompanyNotificationRepository;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses AbstractIntegrationTest's disposable Testcontainers PostgreSQL only.
 * No developer, rehearsal or production database is used.
 */
@DisplayName("F22: company booking notifications")
class BookingNotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CompanyNotificationRepository notifications;

    @Autowired
    RideRepository rides;

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "pickupAddress": "Rruga e Kavajes",
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300,
              "destinationAddress": "Sheshi Skenderbej"
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    @Test
    @DisplayName("searching alone creates no booking alert; selection creates one for the selected company")
    void notificationOnlyOnSuccessfulSelectionAndOnlyForSelectedCompany() throws Exception {
        var selectedCompany = fixtures.bookableCompany();
        var otherCompany = fixtures.bookableCompany();
        String customerToken = tokenFor(fixtures.customer());
        String selectedPartnerToken = tokenFor(selectedCompany.company().getOwner());
        String otherPartnerToken = tokenFor(otherCompany.company().getOwner());

        String search = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/partner/notifications/unread-count")
                        .header("Authorization", bearer(selectedPartnerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));

        long requestId = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();
        List<Number> offerIds = JsonPath.read(
                search,
                "$.taxiOptions[?(@.companyId == %d)].offerId"
                        .formatted(selectedCompany.company().getId()));
        assertThat(offerIds).hasSize(1);

        String selected = mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"offerId":%d}
                                        """.formatted(offerIds.get(0).longValue())))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        long rideId = ((Number) JsonPath.read(selected, "$.rideId")).longValue();

        mockMvc.perform(get("/api/v1/partner/notifications")
                        .header("Authorization", bearer(selectedPartnerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("RIDE_REQUESTED"))
                .andExpect(jsonPath("$.content[0].rideId").value(rideId))
                .andExpect(jsonPath("$.content[0].read").value(false));

        mockMvc.perform(get("/api/v1/partner/notifications/unread-count")
                        .header("Authorization", bearer(selectedPartnerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));

        mockMvc.perform(get("/api/v1/partner/notifications")
                        .header("Authorization", bearer(otherPartnerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/partner/notifications/unread-count")
                        .header("Authorization", bearer(otherPartnerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));

        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelled booking remains historical; partner checks the live queue before acting")
    void cancelledBookingNotificationIsHistorical() throws Exception {
        var company = fixtures.bookableCompany();
        String customerToken = tokenFor(fixtures.customer());
        String partnerToken = tokenFor(company.company().getOwner());

        String search = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long requestId = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();
        long offerId = ((Number) JsonPath.read(search, "$.taxiOptions[0].offerId")).longValue();
        String selection = mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(customerToken))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"offerId":%d}
                                        """.formatted(offerId)))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        long rideId = ((Number) JsonPath.read(selection, "$.rideId")).longValue();
        mockMvc.perform(post("/api/v1/rides/" + rideId + "/cancel")
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isOk());

        assertThat(rides.findById(rideId).orElseThrow().getStatus())
                .isEqualTo(RideStatus.CANCELLED);

        mockMvc.perform(get("/api/v1/partner/notifications")
                        .header("Authorization", bearer(partnerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("RIDE_REQUESTED"))
                .andExpect(jsonPath("$.content[0].rideId").value(rideId));

        mockMvc.perform(get("/api/v1/partner/rides?active=true")
                        .header("Authorization", bearer(partnerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }
}
