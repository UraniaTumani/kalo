package com.kalo.config;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Turns the limiter on for this class only; the rest of the suite runs with it
 * off, because signing in dozens of times a minute from one address is normal
 * for tests and abnormal for users.
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
@DisplayName("Rate limiting on the anonymous endpoints")
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    private static final int LOGIN_LIMIT = 10;
    private static final int PUBLIC_LIMIT = 30;

    @org.springframework.beans.factory.annotation.Autowired
    RateLimitFilter rateLimitFilter;

    /** The filter is a singleton, so counters survive between tests. */
    @org.junit.jupiter.api.BeforeEach
    void clearCounters() {
        rateLimitFilter.resetLimits();
    }

    @Test
    @DisplayName("repeated failed logins are cut off with a 429 in the standard error shape")
    void loginIsLimited() throws Exception {

        User customer = fixtures.customer();

        String body = """
                {"phone":"%s","password":"wrong-password"}
                """.formatted(customer.getPhone());

        // Every one of these is rejected on credentials, not on rate — the
        // point is that guessing is what gets throttled.
        for (int attempt = 1; attempt <= LOGIN_LIMIT; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("a correct password does not bypass the limit")
    void validCredentialsAreLimitedToo() throws Exception {

        User customer = fixtures.customer();

        String body = """
                {"phone":"%s","password":"%s"}
                """.formatted(customer.getPhone(), TestDataFactory.PASSWORD);

        for (int attempt = 1; attempt <= LOGIN_LIMIT; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("registration is limited more tightly than login")
    void registrationIsLimited() throws Exception {

        // Five in five minutes; the sixth is refused even though each request
        // is for a different person.
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(
                            post("/api/v1/auth/register/customer")
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "firstName":"Test","lastName":"Rider",
                                              "phone":"%s","password":"%s"
                                            }
                                            """.formatted(fixtures.nextPhone(), TestDataFactory.PASSWORD))
                    )
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(
                        post("/api/v1/auth/register/customer")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName":"One","lastName":"Too-Many",
                                          "phone":"%s","password":"%s"
                                        }
                                        """.formatted(fixtures.nextPhone(), TestDataFactory.PASSWORD))
                )
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the public availability endpoint is limited")
    void publicEndpointIsLimited() throws Exception {

        String body = """
                {"latitude":41.3275,"longitude":19.8187}
                """;

        for (int attempt = 1; attempt <= PUBLIC_LIMIT; attempt++) {
            mockMvc.perform(
                            post("/api/v1/public/taxi-availability")
                                    .contentType(APPLICATION_JSON)
                                    .content(body)
                    )
                    .andExpect(status().isOk());
        }

        mockMvc.perform(
                        post("/api/v1/public/taxi-availability")
                                .contentType(APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("opening a password recovery is throttled tightly")
    void forgotPasswordIsLimited() throws Exception {

        String body = """
                {"phone":"+355690000009"}
                """;

        /*
         * Three in a quarter of an hour, tighter than registration: each one
         * costs an administrator a telephone call, so the abuse worth stopping
         * is a queue filled faster than a person can work it. The number is
         * unregistered on purpose — the limiter must bite before the caller
         * learns anything either way.
         */
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(
                            post("/api/v1/auth/password/forgot")
                                    .contentType(APPLICATION_JSON)
                                    .content(body)
                    )
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(
                        post("/api/v1/auth/password/forgot")
                                .contentType(APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("authenticated endpoints are not throttled")
    void authenticatedEndpointsAreNotLimited() throws Exception {

        String token = tokenFor(fixtures.customer());

        // Comfortably past every configured limit.
        for (int attempt = 1; attempt <= PUBLIC_LIMIT + 5; attempt++) {
            mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get("/api/v1/me")
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isOk());
        }

        assertThat(true).as("no request was throttled").isTrue();
    }
}
