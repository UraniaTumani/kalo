package com.kalo.auth;

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
 * Password recovery, end to end.
 *
 * The tests that matter here are the ones about what the API refuses to tell
 * you. A recovery endpoint is reachable without signing in, so every
 * distinction it exposes — this number is registered, this account is
 * suspended, this code has expired rather than never existed — is a fact about
 * somebody else's account handed to whoever asks. Several of these tests assert
 * that two very different situations produce byte-identical responses, which is
 * the sort of property that quietly stops being true during a refactor.
 */
class PasswordResetIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "BrandNewPass456!";

    /* ------------------------------------------------------- requesting */

    @Test
    @DisplayName("an unknown number and a real one are indistinguishable")
    void unknownAndKnownNumbersLookTheSame() throws Exception {

        User customer = fixtures.customer();

        String forReal = forgot(customer.getPhone());
        String forNobody = forgot("+355690000001");

        assertThat(forReal).isEqualTo(forNobody);
    }

    @Test
    @DisplayName("a request for a real account reaches the admin queue")
    void requestIsQueued() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        forgot(customer.getPhone());

        mockMvc.perform(
                        get("/api/v1/admin/password-resets")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].phone").value(customer.getPhone()));
    }

    @Test
    @DisplayName("a request for an unknown number reaches nobody")
    void unknownNumberIsNotQueued() throws Exception {

        User admin = fixtures.admin();

        forgot("+355690000002");

        mockMvc.perform(
                        get("/api/v1/admin/password-resets")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    /* -------------------------------------------------------- suspended */

    @Test
    @DisplayName("a suspended account is refused, and the refusal is invisible")
    void suspendedAccountIsRefusedSilently() throws Exception {

        User suspended = fixtures.user(UserRole.CUSTOMER, UserStatus.SUSPENDED);
        User active = fixtures.customer();
        User admin = fixtures.admin();

        /* The caller cannot tell the two apart. */
        assertThat(forgot(suspended.getPhone()))
                .isEqualTo(forgot(active.getPhone()));

        /*
         * But only the active one is actionable: a suspended account never
         * reaches the queue, so no administrator can issue it a code by
         * mistake.
         */
        String queue = mockMvc.perform(
                        get("/api/v1/admin/password-resets")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<String>read(queue, "$.content[0].phone"))
                .isEqualTo(active.getPhone());
    }

    @Test
    @DisplayName("suspending between request and issue blocks the code")
    void suspensionAfterRequestBlocksIssue() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        forgot(customer.getPhone());
        long requestId = firstQueuedId(admin);

        jdbcTemplate.update(
                "UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                customer.getId()
        );

        mockMvc.perform(
                        post("/api/v1/admin/password-resets/" + requestId + "/issue")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------- redemption */

    @Test
    @DisplayName("a code issued by an admin sets a new password")
    void happyPath() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = issueCodeFor(customer, admin);

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        /* The new password works and the old one does not. */
        login(customer.getPhone(), NEW_PASSWORD);

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s","password":"%s"}
                                        """.formatted(customer.getPhone(), TestDataFactory.PASSWORD))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a code works once")
    void codeIsSingleUse() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = issueCodeFor(customer, admin);

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        reset(customer.getPhone(), code, "AnotherPass789!")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an expired code is refused")
    void expiredCodeIsRefused() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = issueCodeFor(customer, admin);

        /*
         * Reached through the database because no test can wait a quarter of
         * an hour, and the lifetime is not configurable on purpose.
         */
        jdbcTemplate.update(
                "UPDATE password_reset_requests SET expires_at = now() - interval '1 minute'"
        );

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a wrong code is refused, and burns after five tries")
    void wrongCodeBurnsAfterFiveAttempts() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = issueCodeFor(customer, admin);

        for (int attempt = 0; attempt < 5; attempt++) {
            reset(customer.getPhone(), "WRONGCDE", NEW_PASSWORD)
                    .andExpect(status().isBadRequest());
        }

        /* Even the real code no longer works: the request is spent. */
        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("every failure gives the same answer")
    void failuresAreIndistinguishable() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        /* No request was ever made for this account. */
        String noRequest = refusalMessage(customer.getPhone(), "ABCD3F7H");

        /* This number belongs to nobody at all. */
        String noAccount = refusalMessage("+355690000003", "ABCD3F7H");

        /* A live request, but the wrong code. */
        issueCodeFor(customer, admin);
        String wrongCode = refusalMessage(customer.getPhone(), "WRONGCDE");

        /*
         * Compared on the message rather than the whole body, because the
         * error shape carries a timestamp — the distinctions being guarded
         * against are the ones that would name the cause.
         */
        assertThat(noRequest).isEqualTo(noAccount);
        assertThat(wrongCode).isEqualTo(noAccount);
    }

    /* ------------------------------------------------------- the tokens */

    @Test
    @DisplayName("the code is never stored in the clear")
    void codeIsStoredHashed() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = issueCodeFor(customer, admin);

        String stored = jdbcTemplate.queryForObject(
                "SELECT code_hash FROM password_reset_requests WHERE user_id = ?",
                String.class,
                customer.getId()
        );

        assertThat(stored).isNotNull();
        assertThat(stored).doesNotContain(code);
        /* Bcrypt, not a fast digest: the code is short enough to grind. */
        assertThat(stored).startsWith("$2");
    }

    @Test
    @DisplayName("a reset drops every other session")
    void resetRevokesRefreshTokens() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        /* Two sessions, as if a phone and a laptop were both signed in. */
        login(customer.getPhone(), TestDataFactory.PASSWORD);
        login(customer.getPhone(), TestDataFactory.PASSWORD);

        Integer liveBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                customer.getId()
        );
        assertThat(liveBefore).isGreaterThanOrEqualTo(2);

        String code = issueCodeFor(customer, admin);

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        Integer liveAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                customer.getId()
        );
        assertThat(liveAfter).isZero();
    }

    /* ---------------------------------------------------------- the ACL */

    @Test
    @DisplayName("only an administrator can see the queue or issue a code")
    void queueIsAdminOnly() throws Exception {

        User customer = fixtures.customer();
        User partner = fixtures.user(UserRole.PARTNER, UserStatus.ACTIVE);

        forgot(customer.getPhone());

        mockMvc.perform(get("/api/v1/admin/password-resets"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get("/api/v1/admin/password-resets")
                                .header("Authorization", bearer(tokenFor(customer)))
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get("/api/v1/admin/password-resets")
                                .header("Authorization", bearer(tokenFor(partner)))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a partner can recover too, not only a customer")
    void partnersCanRecover() throws Exception {

        User partner = fixtures.user(UserRole.PARTNER, UserStatus.ACTIVE);
        User admin = fixtures.admin();

        String code = issueCodeFor(partner, admin);

        reset(partner.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        login(partner.getPhone(), NEW_PASSWORD);
    }

    /* ------------------------------------------------------ the account */

    @Test
    @DisplayName("a reset changes the password and nothing else")
    void resetLeavesTheAccountAlone() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = issueCodeFor(customer, admin);

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        var row = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name, phone, email, role, status FROM users WHERE id = ?",
                customer.getId()
        );

        assertThat(row.get("first_name")).isEqualTo(customer.getFirstName());
        assertThat(row.get("last_name")).isEqualTo(customer.getLastName());
        assertThat(row.get("phone")).isEqualTo(customer.getPhone());
        assertThat(row.get("role")).isEqualTo(UserRole.CUSTOMER.name());
        assertThat(row.get("status")).isEqualTo(UserStatus.ACTIVE.name());
    }

    /* ---------------------------------------------------------- helpers */

    /** Opens a recovery and returns the response body, for comparing. */
    private String forgot(String phone) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/password/forgot")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s"}
                                        """.formatted(phone))
                )
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private long firstQueuedId(User admin) throws Exception {

        String body = mockMvc.perform(
                        get("/api/v1/admin/password-resets")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return ((Number) JsonPath.read(body, "$.content[0].id")).longValue();
    }

    /** The whole flow up to the point a code exists. */
    private String issueCodeFor(User user, User admin) throws Exception {

        forgot(user.getPhone());

        long requestId = firstQueuedId(admin);

        String body = mockMvc.perform(
                        post("/api/v1/admin/password-resets/" + requestId + "/issue")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.code");
    }

    private org.springframework.test.web.servlet.ResultActions reset(
            String phone,
            String code,
            String newPassword
    ) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","newPassword":"%s"}
                                """.formatted(phone, code, newPassword))
        );
    }

    /** The refusal message, for asserting two different failures read alike. */
    private String refusalMessage(String phone, String code) throws Exception {

        String body = reset(phone, code, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.message");
    }
}
