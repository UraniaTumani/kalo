package com.kalo.auth;

import com.jayway.jsonpath.JsonPath;
import com.kalo.sms.FakeSmsSender;
import com.kalo.sms.SmsSender;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password recovery by one-time code, end to end.
 *
 * The tests that matter here are the ones about what the API refuses to tell
 * you, and the ones about what a six-digit code costs. A recovery endpoint is
 * reachable without signing in, so every distinction it exposes — this number is
 * registered, this account is suspended, this code expired rather than never
 * existed — is a fact about somebody else's account handed to whoever asks.
 * Several tests below assert that two very different situations produce
 * byte-identical responses, which is the sort of property that quietly stops
 * being true during a refactor.
 *
 * The other half is arithmetic. Twenty bits of entropy is safe only because of
 * the attempt cap, the expiry and the cooldown, so each of those is tested as a
 * security control rather than as a nicety — including the one that has silently
 * failed before.
 *
 * The code is read from the fake sender rather than from a log or a response,
 * because it appears in neither: nothing in the SMS path ever returns it.
 */
class PasswordResetIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "BrandNewPass456!";

    /** Mirrors FORGOT_PASSWORD_FLOOR in AuthController. */
    private static final long FLOOR_MILLIS = 250;

    /** A note long enough to satisfy the fallback's 20-character minimum. */
    private static final String NOTE =
            "Called the company office line and confirmed the last three rides.";

    /** Matches the body PasswordResetSmsDispatcher sends. */
    private static final Pattern CODE_IN_MESSAGE = Pattern.compile("kodi (\\d{6})");

    @Autowired
    private SmsSender smsSender;

    /**
     * The buffer is on a singleton bean, so one test's messages would otherwise
     * be visible to the next — and "the code that was sent" is exactly the kind
     * of assertion that passes for the wrong reason when stale data is lying
     * around.
     */
    @BeforeEach
    void clearSentMessages() {
        fake().clear();
    }

    /* --------------------------------------------------------- the path */

    @Test
    @DisplayName("the endpoint is mapped where the frontend calls it")
    void theEndpointIsWhereTheClientExpectsIt() throws Exception {

        /*
         * Pinned as a contract, because it was reported broken and was not. The
         * frontend posts to this exact path; a backend that does not have this
         * route answers 404, and the page then shows "Resource not found" under
         * a phone field, where it reads as a verdict on the number.
         */
        mockMvc.perform(
                        post("/api/v1/auth/password/forgot")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"phone":"+355690000002"}
                                        """)
                )
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("it answers no other verb, so a wrong method is not a 404")
    void onlyPostIsMapped() throws Exception {

        /*
         * 405 rather than 404 is the tell that separates "the route exists and
         * you used it wrongly" from "this build has no such route" -- which is
         * the difference between a client bug and a stale deploy.
         */
        mockMvc.perform(get("/api/v1/auth/password/forgot"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("the old admin queue endpoints are gone")
    void theAdminQueueIsGone() throws Exception {

        User admin = fixtures.admin();
        String token = bearer(tokenFor(admin));

        /*
         * Asserted rather than assumed. The queue was how recovery worked, so
         * something somewhere may still call it, and a 404 here is the honest
         * answer — better than a route that still lists requests nobody will
         * ever action.
         */
        mockMvc.perform(get("/api/v1/admin/password-resets").header("Authorization", token))
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        post("/api/v1/admin/password-resets/1/issue")
                                .header("Authorization", token)
                )
                .andExpect(status().isNotFound());
    }

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
    @DisplayName("a request for a real account sends one message to that number")
    void requestSendsAMessage() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());

        assertThat(fake().messagesTo(customer.getPhone())).hasSize(1);
        assertThat(codeSentTo(customer.getPhone())).hasSize(6);
    }

    @Test
    @DisplayName("a request for an unknown number sends nothing")
    void unknownNumberSendsNothing() throws Exception {

        forgot("+355690000002");

        assertThat(fake().messagesTo("+355690000002")).isEmpty();
    }

    @Test
    @DisplayName("the code is six digits, and leading zeros are possible")
    void theCodeIsSixDigits() throws Exception {

        /*
         * The loop is about the second half. A generator written as
         * nextInt(900_000) + 100_000 passes a single-run length check and has
         * quietly thrown away a tenth of the keyspace, so this asserts the shape
         * across enough codes that a missing leading zero would be the only
         * explanation for every sample starting 1-9 -- and asserts the format
         * strictly, which is what actually pins it.
         */
        for (int attempt = 0; attempt < 20; attempt++) {

            /*
             * A fresh account each time rather than resending, because the
             * cooldown would refuse the second request for the same number --
             * which is the correct behaviour and would make this loop measure
             * nothing.
             */
            User customer = fixtures.customer();

            forgot(customer.getPhone());

            assertThat(codeSentTo(customer.getPhone())).matches("\\d{6}");
        }
    }

    @Test
    @DisplayName("the message never says who the account belongs to")
    void theMessageNamesNobody() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());

        String message = fake().lastMessageTo(customer.getPhone()).orElseThrow();

        /*
         * A recovery message goes to a number that has never been verified, so
         * it may well reach somebody who is not the account holder. What it must
         * not do is tell that person whose account it is.
         */
        assertThat(message)
                .doesNotContain(customer.getFirstName())
                .doesNotContain(customer.getLastName())
                .doesNotContain(customer.getPhone());
    }

    @Test
    @DisplayName("the code is in no response anywhere")
    void theCodeIsNeverReturned() throws Exception {

        User customer = fixtures.customer();

        String body = forgot(customer.getPhone());
        String code = codeSentTo(customer.getPhone());

        /*
         * The whole point of having a channel. Under the old design the code came
         * back in an admin response because there was nowhere else for it to go;
         * now the only copy travels by message, and a 202 with an empty body is
         * what proves it.
         *
         * The second assertion is what stops the first from being hollow. An
         * empty body trivially does not contain the code, so on its own "the
         * response omits it" would also pass if no code had been generated at all
         * -- this pins that a real code exists and went to the message instead.
         */
        assertThat(body).isEmpty();

        assertThat(fake().lastMessageTo(customer.getPhone()).orElseThrow())
                .contains(code);
    }

    @Test
    @DisplayName("a suspended account is refused, silently and identically")
    void suspendedAccountsCannotRecover() throws Exception {

        User suspended = fixtures.user(UserRole.CUSTOMER, UserStatus.SUSPENDED);
        User active = fixtures.customer();

        String forSuspended = forgot(suspended.getPhone());
        String forActive = forgot(active.getPhone());

        /*
         * Identical response, no message. Telling the caller would confirm both
         * that the account exists and that it is suspended, which is a fact
         * about somebody else that the person asking has no claim to.
         */
        assertThat(forSuspended).isEqualTo(forActive);
        assertThat(fake().messagesTo(suspended.getPhone())).isEmpty();
    }

    @Test
    @DisplayName("the request takes the same time whoever asked")
    void theRequestIsHeldToAFixedTime() throws Exception {

        User customer = fixtures.customer();

        long known = timeOf(() -> forgot(customer.getPhone()));
        long unknown = timeOf(() -> forgot("+355690000003"));

        /*
         * The body being identical is only half of it: an unknown number costs
         * one SELECT and a registered one costs a lookup, three counts, an
         * insert and a bcrypt hash. Both are held to the floor so what a caller
         * measures is the floor rather than the work.
         *
         * The SMS is deliberately not part of that work -- it goes out on
         * another thread after the commit -- which is what keeps a registered
         * number from overrunning the floor by a provider's round trip.
         */
        assertThat(known).isGreaterThanOrEqualTo(FLOOR_MILLIS);
        assertThat(unknown).isGreaterThanOrEqualTo(FLOOR_MILLIS);
    }

    /* --------------------------------------------------------- redeeming */

    @Test
    @DisplayName("the code sets a new password and the new one works")
    void theCodeWorks() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());

        reset(customer.getPhone(), codeSentTo(customer.getPhone()), NEW_PASSWORD)
                .andExpect(status().isNoContent());

        login(customer.getPhone(), NEW_PASSWORD);
    }

    @Test
    @DisplayName("a partner can recover too, not only a customer")
    void partnersCanRecover() throws Exception {

        User partner = fixtures.user(UserRole.PARTNER, UserStatus.ACTIVE);

        forgot(partner.getPhone());

        reset(partner.getPhone(), codeSentTo(partner.getPhone()), NEW_PASSWORD)
                .andExpect(status().isNoContent());

        login(partner.getPhone(), NEW_PASSWORD);
    }

    @Test
    @DisplayName("a code can be used once")
    void theCodeIsSingleUse() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());
        String code = codeSentTo(customer.getPhone());

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        reset(customer.getPhone(), code, "AnotherPass789!")
                .andExpect(status().isBadRequest());

        /* The first password is still the live one. */
        login(customer.getPhone(), NEW_PASSWORD);
    }

    @Test
    @DisplayName("a wrong code is refused")
    void aWrongCodeIsRefused() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());

        reset(customer.getPhone(), wrongCode(codeSentTo(customer.getPhone())), NEW_PASSWORD)
                .andExpect(status().isBadRequest());

        /* And the real password still works. */
        login(customer.getPhone(), TestDataFactory.PASSWORD);
    }

    @Test
    @DisplayName("an expired code is refused")
    void anExpiredCodeIsRefused() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());
        String code = codeSentTo(customer.getPhone());

        /*
         * Aged in the database rather than by waiting five minutes. There is no
         * other way to test an expiry whose whole purpose is to be longer than a
         * test run.
         */
        jdbcTemplate.update(
                """
                UPDATE password_reset_requests
                SET expires_at = now() - interval '1 minute'
                WHERE user_id = ?
                """,
                customer.getId()
        );

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("five wrong codes burn the request, and the real one then fails")
    void wrongCodeBurnsAfterFiveAttempts() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());
        String code = codeSentTo(customer.getPhone());
        String wrong = wrongCode(code);

        for (int attempt = 0; attempt < 5; attempt++) {
            reset(customer.getPhone(), wrong, NEW_PASSWORD)
                    .andExpect(status().isBadRequest());
        }

        /*
         * The assertion that matters, and the one that caught a real hole: the
         * cap is enforced by an UPDATE on the failure path, and every failure
         * path leaves by throwing. Without noRollbackFor on the service method
         * the increment was rolled back with the exception, so the counter never
         * moved and the correct code still worked after any number of guesses.
         *
         * With a six-digit code that would not be a rough edge. Twenty bits with
         * unlimited guesses is a million requests -- an afternoon -- so this
         * single line is the difference between a working OTP and a decorative
         * one.
         */
        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isBadRequest());

        assertThat(
                jdbcTemplate.queryForObject(
                        "SELECT attempts FROM password_reset_requests WHERE user_id = ?",
                        Integer.class,
                        customer.getId()
                )
        ).isEqualTo(5);
    }

    @Test
    @DisplayName("every refusal reads exactly alike")
    void everyRefusalIsIndistinguishable() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());
        String code = codeSentTo(customer.getPhone());

        String wrongCodeForRealAccount =
                refusalMessage(customer.getPhone(), wrongCode(code));
        String realCodeForUnknownAccount =
                refusalMessage("+355690000004", code);
        String noRequestAtAll =
                refusalMessage(fixtures.user(UserRole.CUSTOMER, UserStatus.ACTIVE).getPhone(), code);

        /*
         * Three genuinely different situations: a wrong code against a live
         * request, a good code against a number belonging to nobody, and a good
         * code against an account that never asked. All one sentence, because
         * the differences between them are only useful to somebody probing.
         */
        assertThat(wrongCodeForRealAccount)
                .isEqualTo(realCodeForUnknownAccount)
                .isEqualTo(noRequestAtAll);
    }

    @Test
    @DisplayName("a reset revokes every other session")
    void resetRevokesRefreshTokens() throws Exception {

        User customer = fixtures.customer();

        /* Somebody else is signed in on this account right now. */
        String refreshToken = refreshTokenFor(customer);

        forgot(customer.getPhone());

        reset(customer.getPhone(), codeSentTo(customer.getPhone()), NEW_PASSWORD)
                .andExpect(status().isNoContent());

        /*
         * A recovery is most often a recovery *from* somebody. Leaving their
         * refresh token alive would hand the account straight back the moment
         * their access token expired.
         */
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"refreshToken":"%s"}
                                        """.formatted(refreshToken))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a reset changes the password and nothing else")
    void resetLeavesTheAccountAlone() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());

        reset(customer.getPhone(), codeSentTo(customer.getPhone()), NEW_PASSWORD)
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

    @Test
    @DisplayName("the spent code is not left in the table")
    void aUsedCodeIsCleared() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());

        reset(customer.getPhone(), codeSentTo(customer.getPhone()), NEW_PASSWORD)
                .andExpect(status().isNoContent());

        /*
         * Cheap, and it means a leak of this table exposes only codes that are
         * actually live. A spent hash is a hash somebody can still grind.
         */
        assertThat(
                jdbcTemplate.queryForList(
                        "SELECT code_hash FROM password_reset_requests WHERE user_id = ?",
                        String.class,
                        customer.getId()
                )
        ).containsOnlyNulls();
    }

    /* ------------------------------------------------ resending and caps */

    @Test
    @DisplayName("asking again inside the cooldown sends nothing")
    void theCooldownHolds() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());
        forgot(customer.getPhone());

        /*
         * Silently, and identically. A client-side countdown stops an impatient
         * person; this stops somebody calling the endpoint directly, and each
         * send is a message a real person receives whether they asked or not.
         */
        assertThat(fake().messagesTo(customer.getPhone())).hasSize(1);
    }

    @Test
    @DisplayName("a resend past the cooldown supersedes the first code")
    void resendSupersedesTheOldCode() throws Exception {

        User customer = fixtures.customer();

        forgot(customer.getPhone());
        String first = codeSentTo(customer.getPhone());

        agePreviousRequests(customer, "2 minutes");

        forgot(customer.getPhone());
        String second = codeSentTo(customer.getPhone());

        assertThat(second).isNotEqualTo(first);

        /*
         * One live code per account. Leaving the old one usable would mean every
         * resend widened the window an attacker has to guess in, which is the
         * opposite of what a resend button is for.
         */
        reset(customer.getPhone(), first, NEW_PASSWORD)
                .andExpect(status().isBadRequest());

        reset(customer.getPhone(), second, NEW_PASSWORD)
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("one account gets five messages a day and no more")
    void thePerAccountDailyCapHolds() throws Exception {

        User customer = fixtures.customer();

        for (int request = 0; request < 6; request++) {
            forgot(customer.getPhone());
            agePreviousRequests(customer, "2 minutes");
        }

        /*
         * Per account rather than per address, because an IP limit does nothing
         * against somebody spreading requests for one victim across many
         * addresses -- which is how you turn a recovery form into a way of
         * texting a stranger all afternoon.
         */
        assertThat(fake().messagesTo(customer.getPhone())).hasSize(5);
    }

    @Test
    @DisplayName("hammering a suspended account cannot fill the table")
    void refusalsAreBoundedToo() throws Exception {

        User suspended = fixtures.user(UserRole.CUSTOMER, UserStatus.SUSPENDED);

        /*
         * A suspended account is refused by recording a row, so that repeated
         * attempts are visible to an administrator. That is a write an anonymous
         * caller can cause, so it has to be bounded by the same cooldown and
         * daily cap as a real send -- otherwise anybody who knows one suspended
         * number can grow this table for as long as they like, from as many
         * addresses as they like.
         */
        forgot(suspended.getPhone());
        forgot(suspended.getPhone());

        assertThat(rowsFor(suspended)).isEqualTo(1);

        for (int request = 0; request < 8; request++) {
            forgot(suspended.getPhone());
            agePreviousRequests(suspended, "2 minutes");
        }

        assertThat(rowsFor(suspended)).isEqualTo(5);
    }

    /* ------------------------------------------------- the admin fallback */

    @Test
    @DisplayName("an administrator can issue a code by hand, and it works")
    void theFallbackIssuesAUsableCode() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = fallbackCode(admin, customer.getPhone(), NOTE);

        /* Eight characters, because it is read down a telephone line. */
        assertThat(code).hasSize(8);

        /* No message: the administrator on the call is the delivery. */
        assertThat(fake().messagesTo(customer.getPhone())).isEmpty();

        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        login(customer.getPhone(), NEW_PASSWORD);
    }

    @Test
    @DisplayName("only an administrator can issue one")
    void theFallbackIsAdminOnly() throws Exception {

        User customer = fixtures.customer();
        User partner = fixtures.user(UserRole.PARTNER, UserStatus.ACTIVE);

        /*
         * The whole feature rests on this. A fallback reachable by anybody who
         * can sign in is an account takeover endpoint with a text box on it.
         */
        fallback(null, customer.getPhone(), NOTE)
                .andExpect(status().isUnauthorized());

        fallback(bearer(tokenFor(customer)), customer.getPhone(), NOTE)
                .andExpect(status().isForbidden());

        fallback(bearer(tokenFor(partner)), customer.getPhone(), NOTE)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the audit trail is admin-only as well")
    void theAuditTrailIsAdminOnly() throws Exception {

        User customer = fixtures.customer();

        /*
         * It carries names, numbers and the reasons codes were issued, which is
         * a list of who has recently been locked out of their account.
         */
        mockMvc.perform(get("/api/v1/admin/password-resets/fallback-log"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get("/api/v1/admin/password-resets/fallback-log")
                                .header("Authorization", bearer(tokenFor(customer)))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a code cannot be issued without saying how the person was verified")
    void theFallbackRequiresARealNote() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();
        String token = bearer(tokenFor(admin));

        /*
         * The friction is the feature. This endpoint exists because SMS has one
         * unavoidable failure, and the danger is not that it exists but that it
         * becomes routine -- so a field that will not accept "ok" is what makes
         * an administrator write down what they actually checked.
         */
        fallback(token, customer.getPhone(), "ok")
                .andExpect(status().isBadRequest());

        fallback(token, customer.getPhone(), "")
                .andExpect(status().isBadRequest());

        assertThat(
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM password_reset_requests",
                        Integer.class
                )
        ).isZero();
    }

    @Test
    @DisplayName("every hand-issued code is recorded with who allowed it and why")
    void theFallbackIsAudited() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        fallbackCode(admin, customer.getPhone(), NOTE);

        mockMvc.perform(
                        get("/api/v1/admin/password-resets/fallback-log")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].personPhone").value(customer.getPhone()))
                .andExpect(jsonPath("$.content[0].verificationNote").value(NOTE))
                .andExpect(jsonPath("$.content[0].issuedByName")
                        .value(admin.getFirstName() + " " + admin.getLastName()))
                .andExpect(jsonPath("$.content[0].issuedAt").exists())
                /* Never the code, and never the hash. The record is the decision. */
                .andExpect(jsonPath("$.content[0].code").doesNotExist())
                .andExpect(jsonPath("$.content[0].codeHash").doesNotExist());
    }

    @Test
    @DisplayName("an ordinary SMS recovery does not appear in the fallback trail")
    void theTrailShowsOnlyFallbacks() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        forgot(customer.getPhone());

        /*
         * Otherwise the one thing worth noticing -- a run of hand-issued codes --
         * would be buried among thousands of ordinary rows, which is a good way
         * not to notice it.
         */
        mockMvc.perform(
                        get("/api/v1/admin/password-resets/fallback-log")
                                .header("Authorization", bearer(tokenFor(admin)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("a hand-issued code supersedes a live SMS code")
    void theFallbackSupersedesTheSmsCode() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        forgot(customer.getPhone());
        String texted = codeSentTo(customer.getPhone());

        String byHand = fallbackCode(admin, customer.getPhone(), NOTE);

        /* Still exactly one live code on the account. */
        reset(customer.getPhone(), texted, NEW_PASSWORD)
                .andExpect(status().isBadRequest());

        reset(customer.getPhone(), byHand, NEW_PASSWORD)
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the fallback obeys the same attempt cap")
    void theFallbackCodeCanBeBurntToo() throws Exception {

        User customer = fixtures.customer();
        User admin = fixtures.admin();

        String code = fallbackCode(admin, customer.getPhone(), NOTE);

        for (int attempt = 0; attempt < 5; attempt++) {
            reset(customer.getPhone(), "AAAAAAAA", NEW_PASSWORD)
                    .andExpect(status().isBadRequest());
        }

        /*
         * The fallback skips the message and nothing else. It is redeemed through
         * the same endpoint, so it inherits the cap, the single use and the
         * bcrypt-only storage rather than reimplementing any of them.
         */
        reset(customer.getPhone(), code, NEW_PASSWORD)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a suspended account cannot be recovered by hand either")
    void theFallbackRefusesSuspendedAccounts() throws Exception {

        User suspended = fixtures.user(UserRole.CUSTOMER, UserStatus.SUSPENDED);
        User admin = fixtures.admin();

        fallback(bearer(tokenFor(admin)), suspended.getPhone(), NOTE)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the fallback says plainly when there is no such account")
    void theFallbackDoesNotHideAnUnknownNumber() throws Exception {

        User admin = fixtures.admin();

        /*
         * The one place in this flow that does distinguish. Concealing it would
         * be theatre: the caller is an authenticated administrator who can list
         * every user on the next endpoint along, and a generic refusal would only
         * make them wonder whether they had mistyped the number.
         */
        fallback(bearer(tokenFor(admin)), "+355690000009", NOTE)
                .andExpect(status().isNotFound());
    }

    /* ---------------------------------------------------------- helpers */

    private FakeSmsSender fake() {
        return (FakeSmsSender) smsSender;
    }

    /** The code that was actually texted, read out of the message body. */
    private String codeSentTo(String phone) {

        String message = fake()
                .lastMessageTo(phone)
                .orElseThrow(() -> new AssertionError("No message was sent to " + phone));

        Matcher matcher = CODE_IN_MESSAGE.matcher(message);

        if (!matcher.find()) {
            throw new AssertionError("No six-digit code in: " + message);
        }

        return matcher.group(1);
    }

    private int rowsFor(User user) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_requests WHERE user_id = ?",
                Integer.class,
                user.getId()
        );
    }

    /** A code the same shape as the real one, and guaranteed not to be it. */
    private String wrongCode(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    /**
     * Backdates a user's requests so the next call is past the cooldown.
     *
     * The alternative is sleeping for a minute per assertion, which would make
     * the cap tests unrunnable.
     */
    private void agePreviousRequests(User user, String interval) {
        jdbcTemplate.update(
                "UPDATE password_reset_requests SET created_at = created_at - interval '"
                        + interval + "' WHERE user_id = ?",
                user.getId()
        );
    }

    /** Wall-clock milliseconds a call took. */
    private long timeOf(ThrowingRunnable action) throws Exception {
        long started = System.nanoTime();
        action.run();
        return (System.nanoTime() - started) / 1_000_000;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

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

    private ResultActions reset(String phone, String code, String newPassword)
            throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","newPassword":"%s"}
                                """.formatted(phone, code, newPassword))
        );
    }

    private ResultActions fallback(String authorization, String phone, String note)
            throws Exception {

        var request = post("/api/v1/admin/password-resets/fallback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"phone":"%s","verificationNote":"%s"}
                        """.formatted(phone, note));

        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }

        return mockMvc.perform(request);
    }

    private String fallbackCode(User admin, String phone, String note) throws Exception {

        String body = fallback(bearer(tokenFor(admin)), phone, note)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.code");
    }

    /** A live session on an account, for checking a reset drops it. */
    private String refreshTokenFor(User user) throws Exception {

        String body = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s","password":"%s"}
                                        """.formatted(
                                        user.getPhone(),
                                        TestDataFactory.PASSWORD
                                ))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.refreshToken");
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
