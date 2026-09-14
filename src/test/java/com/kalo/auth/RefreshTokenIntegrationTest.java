package com.kalo.auth;

import com.jayway.jsonpath.JsonPath;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The half of a session that outlives the access token.
 *
 * A driver signs in at the start of a shift and must still be signed in at the
 * end of it, without that convenience turning into a credential nobody can take
 * away.
 */
@DisplayName("Refresh tokens")
class RefreshTokenIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("signing in returns a refresh token and the access token lifetime")
    void loginIssuesAPair() throws Exception {

        User customer = fixtures.customer();

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content(credentials(customer))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    @DisplayName("a refresh token buys a working access token")
    void refreshReturnsAUsableAccessToken() throws Exception {

        User customer = fixtures.customer();

        String refreshed = refresh(refreshTokenFor(customer));

        String accessToken = JsonPath.read(refreshed, "$.accessToken");

        // The point of the exercise: the new token actually authenticates.
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(customer.getPhone()));
    }

    @Test
    @DisplayName("every refresh rotates the refresh token")
    void refreshRotates() throws Exception {

        User customer = fixtures.customer();

        String first = refreshTokenFor(customer);
        String second = JsonPath.read(refresh(first), "$.refreshToken");

        assertThat(second)
                .as("a used refresh token is replaced, not re-issued")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("a refresh token cannot be used twice")
    void reusingARotatedTokenIsRejected() throws Exception {

        User customer = fixtures.customer();

        String first = refreshTokenFor(customer);
        refresh(first);

        /*
         * Replay of a spent token: the legitimate client has moved on to the
         * replacement, so this is either an attacker or a bug.
         */
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(first))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown refresh token is rejected")
    void unknownTokenIsRejected() throws Exception {

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody("not-a-token-anyone-issued"))
                )
                .andExpect(status().isUnauthorized());
    }

    /**
     * The expiry itself, which nothing covered.
     *
     * A refresh token lasts thirty days, so no test can wait one out; the row's
     * expiry is moved into the past instead. The token is otherwise perfectly
     * valid — present, unrevoked, and belonging to an active user — so this is
     * the only thing that can refuse it, and if the expiry check were ever lost
     * a stolen token would work forever.
     */
    @Test
    @DisplayName("a refresh token past its expiry is refused")
    void expiredTokenIsRejected() throws Exception {

        User customer = fixtures.customer();
        String refreshToken = refreshTokenFor(customer);

        jdbcTemplate.update(
                """
                UPDATE refresh_tokens
                SET expires_at = now() - interval '1 minute'
                WHERE revoked_at IS NULL
                """
        );

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(refreshToken))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired token is refused even though the account is fine")
    void expiryIsAboutTheTokenNotTheUser() throws Exception {

        User customer = fixtures.customer();
        String refreshToken = refreshTokenFor(customer);

        jdbcTemplate.update(
                """
                UPDATE refresh_tokens
                SET expires_at = now() - interval '1 minute'
                WHERE revoked_at IS NULL
                """
        );

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(refreshToken))
                )
                .andExpect(status().isUnauthorized());

        /* Signing in again works, so the account was never the problem. */
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content(credentials(customer))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    @DisplayName("signing out revokes the refresh token")
    void logoutRevokes() throws Exception {

        User customer = fixtures.customer();
        String refreshToken = refreshTokenFor(customer);

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(refreshToken))
                )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(refreshToken))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("signing out twice is not an error")
    void logoutIsIdempotent() throws Exception {

        User customer = fixtures.customer();
        String refreshToken = refreshTokenFor(customer);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(
                            post("/api/v1/auth/logout")
                                    .contentType(APPLICATION_JSON)
                                    .content(refreshBody(refreshToken))
                    )
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    @DisplayName("suspending a user kills the refresh token, not just the access token")
    void suspensionRevokesRefreshTokens() throws Exception {

        User customer = fixtures.customer();
        String refreshToken = refreshTokenFor(customer);

        User admin = fixtures.admin();

        mockMvc.perform(
                        post("/api/v1/admin/users/" + customer.getId() + "/suspend")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk());

        /*
         * Without this, suspension would only take effect for an hour's worth of
         * access token and the user could mint a fresh one for thirty days.
         */
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(refreshToken))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a blank refresh token is a validation error, not a server error")
    void blankTokenIsRejected() throws Exception {

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(""))
                )
                .andExpect(status().isBadRequest());
    }

    /* ----------------------------------------------------------- helpers */

    private String credentials(User user) {
        return """
                {"phone":"%s","password":"%s"}
                """.formatted(user.getPhone(), TestDataFactory.PASSWORD);
    }

    private String refreshBody(String token) {
        return """
                {"refreshToken":"%s"}
                """.formatted(token);
    }

    /** Signs in and keeps the refresh half. */
    private String refreshTokenFor(User user) throws Exception {

        String body = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content(credentials(user))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.refreshToken");
    }

    private String refresh(String refreshToken) throws Exception {

        return mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(APPLICATION_JSON)
                                .content(refreshBody(refreshToken))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
