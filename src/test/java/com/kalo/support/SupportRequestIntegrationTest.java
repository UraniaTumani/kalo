package com.kalo.support;

import com.kalo.partner.entity.TaxiCompany;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Help & Support.
 *
 * Most of this file is about who can read what. A support request carries a
 * person's own account of something going wrong, often with a phone number and
 * a complaint about a named driver, so "only mine" is the whole feature as far
 * as the submitter is concerned.
 */
@DisplayName("Help & Support")
class SupportRequestIntegrationTest extends AbstractIntegrationTest {

    private static final String BODY = """
            {"category":"RIDE_ISSUE","subject":"Driver took a long route",
             "message":"The fare was higher than I expected."}
            """;

    private static final String OTHER_BODY = """
            {"category":"PAYMENT","subject":"Charged twice",
             "message":"My card shows two payments for one ride."}
            """;

    @Test
    @DisplayName("a customer can file a request and read it back")
    void customerCanFileAndRead() throws Exception {

        User customer = fixtures.customer();

        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(customer)))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.subject").value("Driver took a long route"));

        mockMvc.perform(
                        get("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(customer)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].category").value("RIDE_ISSUE"));
    }

    @Test
    @DisplayName("a partner files under the PARTNER role without a separate endpoint")
    void partnerFilesUnderPartnerRole() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        User partner = company.getOwner();
        User admin = fixtures.admin();

        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(partner)))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isCreated());

        /*
         * The role is not in the request body — it is taken from the token — so
         * this is also the check that a caller cannot file as somebody else's
         * role.
         */
        mockMvc.perform(
                        get("/api/v1/admin/support/requests")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("PARTNER"));
    }

    @Test
    @DisplayName("one customer never sees another customer's request")
    void requestsAreScopedToTheirOwner() throws Exception {

        User first = fixtures.customer();
        User second = fixtures.customer();

        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(first)))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(second)))
                                .contentType(APPLICATION_JSON)
                                .content(OTHER_BODY)
                )
                .andExpect(status().isCreated());

        // Two requests exist; each account sees exactly one of them.
        mockMvc.perform(
                        get("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(first)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].subject")
                        .value("Driver took a long route"));

        mockMvc.perform(
                        get("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(second)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].subject").value("Charged twice"));
    }

    @Test
    @DisplayName("a customer cannot reach the admin queue")
    void customerCannotReachAdminQueue() throws Exception {

        User customer = fixtures.customer();

        mockMvc.perform(
                        get("/api/v1/admin/support/requests")
                                .header("Authorization", bearer(tokenFor(customer)))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a partner cannot reach the admin queue either")
    void partnerCannotReachAdminQueue() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();

        mockMvc.perform(
                        get("/api/v1/admin/support/requests")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an anonymous caller cannot file or read anything")
    void anonymousIsRefused() throws Exception {

        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/support/requests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the submitter's view carries no internal detail")
    void submitterViewIsNarrow() throws Exception {

        User customer = fixtures.customer();

        /*
         * The admin response type carries the submitter's name and phone; the
         * submitter's own does not. Asserting the absence is what stops a
         * future "just add the field" from quietly widening this.
         */
        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(customer)))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.userPhone").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    @DisplayName("an admin sees every request and can move it through the statuses")
    void adminSeesAllAndUpdatesStatus() throws Exception {

        User first = fixtures.customer();
        User second = fixtures.customer();
        User admin = fixtures.admin();

        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(first)))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isCreated());

        String created = mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(second)))
                                .contentType(APPLICATION_JSON)
                                .content(OTHER_BODY)
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long requestId = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

        mockMvc.perform(
                        get("/api/v1/admin/support/requests")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(
                        patch("/api/v1/admin/support/requests/" + requestId + "/status")
                                .header("Authorization", bearer(tokenFor(admin)))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"status":"IN_PROGRESS"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // The status filter is what makes the queue usable once it has volume.
        mockMvc.perform(
                        get("/api/v1/admin/support/requests")
                                .header("Authorization", bearer(tokenFor(admin)))
                                .param("status", "OPEN")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // And the submitter sees the admin's decision on their own request.
        mockMvc.perform(
                        get("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(second)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("an empty subject or message is refused")
    void validationRefusesEmptyFields() throws Exception {

        User customer = fixtures.customer();

        mockMvc.perform(
                        post("/api/v1/support/requests")
                                .header("Authorization", bearer(tokenFor(customer)))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"category":"OTHER","subject":"  ","message":""}
                                        """)
                )
                .andExpect(status().isBadRequest());
    }
}
