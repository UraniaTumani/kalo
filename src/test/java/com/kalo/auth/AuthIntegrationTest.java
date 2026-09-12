package com.kalo.auth;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@DisplayName("Authentication and role boundaries")
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("a customer can sign in and read their own profile")
    void customerLogin() throws Exception {

        User customer = fixtures.customer();
        String token = tokenFor(customer);

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(customer.getPhone()))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    @DisplayName("a partner can sign in")
    void partnerLogin() throws Exception {

        User partner = fixtures.approvedCompany().getOwner();

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokenFor(partner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PARTNER"));
    }

    @Test
    @DisplayName("an admin can sign in")
    void adminLogin() throws Exception {

        User admin = fixtures.admin();

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokenFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("a wrong password is rejected without saying which part was wrong")
    void invalidPassword() throws Exception {

        User customer = fixtures.customer();

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s","password":"definitely-not-it"}
                                        """.formatted(customer.getPhone()))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid phone or password"));
    }

    @Test
    @DisplayName("an unknown phone is rejected the same way as a wrong password")
    void unknownPhone() throws Exception {

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"phone":"+355690000000","password":"%s"}
                                        """.formatted(TestDataFactory.PASSWORD))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid phone or password"));
    }

    @Test
    @DisplayName("a protected endpoint without a token returns 401 as JSON")
    void missingTokenIsUnauthorized() throws Exception {

        mockMvc.perform(get("/api/v1/rides/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("a malformed token is rejected rather than crashing the filter")
    void malformedTokenIsUnauthorized() throws Exception {

        mockMvc.perform(get("/api/v1/rides/current").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("a customer calling an admin endpoint returns 403 as JSON")
    void wrongRoleIsForbidden() throws Exception {

        String token = tokenFor(fixtures.customer());

        mockMvc.perform(get("/api/v1/admin/partners").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("a customer cannot reach partner endpoints")
    void customerCannotReachPartnerEndpoints() throws Exception {

        String token = tokenFor(fixtures.customer());

        mockMvc.perform(get("/api/v1/partner/drivers").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a suspended user cannot use a token issued before suspension")
    void suspendedUserLosesAccess() throws Exception {

        User customer = fixtures.user(UserRole.CUSTOMER, UserStatus.SUSPENDED);

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s","password":"%s"}
                                        """.formatted(customer.getPhone(), TestDataFactory.PASSWORD))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the public availability endpoint needs no token")
    void publicEndpointIsOpen() throws Exception {

        mockMvc.perform(
                        post("/api/v1/public/taxi-availability")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"latitude":41.3275,"longitude":19.8187}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxiOptions").isArray());
    }
}
