package com.kalo.user;

import com.jayway.jsonpath.JsonPath;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing your own password.
 *
 * Until now nothing could: {@code PUT /api/v1/me} takes a name and an email,
 * so an administrator handed a password by another administrator had no way to
 * replace it, and anybody who suspected their password was known could only go
 * through recovery.
 *
 * Two properties carry the weight. The current password is required, so a
 * bearer token lifted from a shared machine cannot be turned into permanent
 * ownership of the account. And the change drops every session, which is what
 * makes it useful for removing somebody — including the caller's own, so a new
 * one comes back in the response rather than leaving them to find out.
 */
class ChangePasswordIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "ChangedPass456!";

    @Test
    @DisplayName("a user changes their own password and is handed a working session")
    void happyPath() throws Exception {

        User user = fixtures.customer();

        String body = change(tokenFor(user), TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        /* The returned session works without signing in again. */
        String fresh = JsonPath.read(body, "$.accessToken");

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(fresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(user.getPhone()));

        /* And the new password is the one that signs in from now on. */
        login(user.getPhone(), NEW_PASSWORD);
    }

    @Test
    @DisplayName("the old password stops working")
    void oldPasswordIsDead() throws Exception {

        User user = fixtures.customer();

        change(tokenFor(user), TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s","password":"%s"}
                                        """.formatted(user.getPhone(), TestDataFactory.PASSWORD))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token alone is not enough — the current password is required")
    void wrongCurrentPasswordIsRefused() throws Exception {

        User user = fixtures.customer();

        /*
         * The property that matters most here. Somebody holding a borrowed
         * session must not be able to lock the owner out of their own account.
         */
        change(tokenFor(user), "NotThePassword1!", NEW_PASSWORD)
                .andExpect(status().isUnauthorized());

        /* Unchanged: the original password still signs in. */
        login(user.getPhone(), TestDataFactory.PASSWORD);
    }

    @Test
    @DisplayName("reusing the same password is refused")
    void samePasswordIsRefused() throws Exception {

        User user = fixtures.customer();

        change(tokenFor(user), TestDataFactory.PASSWORD, TestDataFactory.PASSWORD)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a short new password is refused")
    void shortPasswordIsRefused() throws Exception {

        User user = fixtures.customer();

        change(tokenFor(user), TestDataFactory.PASSWORD, "short")
                .andExpect(status().isBadRequest());

        login(user.getPhone(), TestDataFactory.PASSWORD);
    }

    @Test
    @DisplayName("every other session is dropped")
    void everyOtherSessionIsRevoked() throws Exception {

        User user = fixtures.customer();

        /* A phone and a laptop, both signed in. */
        String phoneSession = login(user.getPhone(), TestDataFactory.PASSWORD);

        String laptopRefresh = JsonPath.read(
                loginBody(user.getPhone(), TestDataFactory.PASSWORD), "$.refreshToken"
        );

        Integer liveBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                user.getId()
        );
        assertThat(liveBefore).isGreaterThanOrEqualTo(2);

        change(phoneSession, TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk());

        /*
         * Exactly one live refresh token afterwards: the one just issued to the
         * caller. The laptop's is gone, which is the whole reason somebody
         * changes a password they believe is known.
         */
        Integer liveAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                user.getId()
        );
        assertThat(liveAfter).isEqualTo(1);

        /*
         * And the laptop's refresh token is refused rather than merely
         * uncounted — asserted through the endpoint, because a row marked
         * revoked that the API still honours would be no protection at all.
         *
         * Checked on the refresh token rather than by comparing access tokens:
         * a JWT's iat and exp are whole seconds, so two minted for the same
         * subject in the same second are byte-identical, and an inequality
         * assertion on them fails against perfectly correct behaviour. A
         * refresh token is 256 bits of randomness and cannot repeat.
         */
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"refreshToken":"%s"}
                                        """.formatted(laptopRefresh))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("signing out is not required to keep working afterwards")
    void callerIsNotStranded() throws Exception {

        User user = fixtures.customer();

        String body = change(tokenFor(user), TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String refresh = JsonPath.read(body, "$.refreshToken");

        /* The returned refresh token is live and can rotate. */
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"refreshToken":"%s"}
                                        """.formatted(refresh))
                )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("it needs a session at all")
    void anonymousIsRefused() throws Exception {

        mockMvc.perform(
                        post("/api/v1/me/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"currentPassword":"%s","newPassword":"%s"}
                                        """.formatted(TestDataFactory.PASSWORD, NEW_PASSWORD))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("every role can change their own password")
    void worksForEveryRole() throws Exception {

        User partner = fixtures.user(UserRole.PARTNER, UserStatus.ACTIVE);
        User admin = fixtures.admin();

        change(tokenFor(partner), TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk());
        login(partner.getPhone(), NEW_PASSWORD);

        change(tokenFor(admin), TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk());
        login(admin.getPhone(), NEW_PASSWORD);
    }

    @Test
    @DisplayName("the response never carries a password or a hash")
    void responseLeaksNothing() throws Exception {

        User user = fixtures.customer();

        String body = change(tokenFor(user), TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(NEW_PASSWORD);
        assertThat(body).doesNotContain(TestDataFactory.PASSWORD);
        assertThat(body).doesNotContain("$2");
    }

    @Test
    @DisplayName("it changes the password and nothing else about the account")
    void nothingElseChanges() throws Exception {

        User user = fixtures.customer();

        change(tokenFor(user), TestDataFactory.PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk());

        var row = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name, phone, email, role, status FROM users WHERE id = ?",
                user.getId()
        );

        assertThat(row.get("first_name")).isEqualTo(user.getFirstName());
        assertThat(row.get("last_name")).isEqualTo(user.getLastName());
        assertThat(row.get("phone")).isEqualTo(user.getPhone());
        assertThat(row.get("role")).isEqualTo(UserRole.CUSTOMER.name());
        assertThat(row.get("status")).isEqualTo(UserStatus.ACTIVE.name());
    }

    /* ---------------------------------------------------------- helpers */

    private org.springframework.test.web.servlet.ResultActions change(
            String accessToken,
            String currentPassword,
            String newPassword
    ) throws Exception {
        return mockMvc.perform(
                post("/api/v1/me/password")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"%s"}
                                """.formatted(currentPassword, newPassword))
        );
    }

    private String loginBody(String phone, String password) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s","password":"%s"}
                                        """.formatted(phone, password))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
