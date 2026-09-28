package com.kalo.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider switch, tested without a Spring context.
 *
 * Worth its own test because almost every branch here is about what happens
 * when somebody gets the configuration wrong, and none of it is reachable from
 * an integration test: the suite runs with one provider and one profile.
 *
 * The recurring shape of these assertions is "refuses to start". That is the
 * deliberate design — a misconfigured SMS provider is invisible at runtime,
 * because the send happens on a background thread and the endpoint answers 202
 * either way. Nobody finds out until somebody cannot get into their account.
 */
@DisplayName("SMS configuration")
class SmsConfigTest {

    private static final String BASE_URL = "https://example.api.infobip.com";
    private static final String API_KEY = "test-key-not-a-real-credential";
    private static final String SENDER = "MRTAXI";

    private final SmsConfig config = new SmsConfig();

    private static Environment profile(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    /** The fake provider, which needs none of the Infobip settings. */
    private SmsSender fake(Environment environment, boolean logMessage) {
        return config.smsSender(environment, "log", logMessage, "", "", "");
    }

    private SmsSender infobip(String baseUrl, String apiKey, String sender) {
        return config.smsSender(profile("prod"), "infobip", false, baseUrl, apiKey, sender);
    }

    /* ------------------------------------------------------ the switch */

    @Test
    @DisplayName("an unknown provider fails at startup rather than falling back")
    void anUnknownProviderRefusesToStart() {

        /*
         * The failure mode this exists for: SMS_PROVIDER=infobop, a deployment
         * that starts normally, and codes that silently stop being delivered
         * while everything looks configured. Better to not start.
         */
        assertThatThrownBy(() ->
                config.smsSender(profile("prod"), "twilio", false, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown app.sms.provider")
                .hasMessageContaining("infobip");
    }

    @Test
    @DisplayName("the fake provider is chosen by the default value")
    void theDefaultIsTheFake() {
        assertThat(fake(profile("dev"), true)).isInstanceOf(FakeSmsSender.class);
    }

    @Test
    @DisplayName("infobip is chosen when its settings are supplied")
    void infobipIsChosenWhenConfigured() {

        assertThat(infobip(BASE_URL, API_KEY, SENDER))
                .isInstanceOf(InfobipSmsSender.class);
    }

    /* ------------------------------------------- the missing settings */

    @Test
    @DisplayName("infobip without a base URL refuses to start, and says which setting")
    void infobipNeedsABaseUrl() {

        /*
         * No default is possible: Infobip issues each account its own host of
         * the form xxxxx.api.infobip.com. Guessing one would point a real
         * deployment's traffic at a host that rejects its key, which surfaces
         * as "messages are not arriving" rather than as a configuration error.
         */
        assertThatThrownBy(() -> infobip("", API_KEY, SENDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sms.infobip.base-url")
                .hasMessageContaining("per-account");
    }

    @Test
    @DisplayName("infobip without an API key refuses to start")
    void infobipNeedsAnApiKey() {

        assertThatThrownBy(() -> infobip(BASE_URL, "  ", SENDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sms.infobip.api-key");
    }

    @Test
    @DisplayName("infobip without a sender ID refuses to start")
    void infobipNeedsASenderId() {

        /*
         * Albania, like most of the region, requires an alphanumeric sender ID
         * to be registered before traffic using it is accepted. A default like
         * "MRTAXI" would produce messages the operator rejects, which is harder
         * to diagnose than an application that will not start.
         */
        assertThatThrownBy(() -> infobip(BASE_URL, API_KEY, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sms.infobip.sender")
                .hasMessageContaining("registered");
    }

    @Test
    @DisplayName("a blank setting is treated as missing, not as a value")
    void blankIsMissing() {

        /*
         * Every one of these arrives from the environment through a
         * `${VAR:}` placeholder, so an unset variable reaches this method as an
         * empty string rather than as null. Treating that as "configured" is
         * how a deployment ends up authenticating with the literal header
         * "App ".
         */
        assertThatThrownBy(() -> infobip("   ", "   ", "   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the fake provider does not require the infobip settings")
    void theFakeNeedsNoCredentials() {

        assertThatCode(() -> fake(profile("prod"), false)).doesNotThrowAnyException();
    }

    /* ------------------------------------------------------- the rest */

    @Test
    @DisplayName("the message body is never logged outside development")
    void theBodyIsNotLoggedInProduction() {

        /*
         * Gated on the profile as well as the property, so setting
         * SMS_LOG_MESSAGE=true against a real deployment does not start writing
         * live codes into its logs — and from there into every aggregator and
         * pasted support ticket they reach.
         */
        assertThat(((FakeSmsSender) fake(profile("prod"), true)).revealsMessage()).isFalse();
        assertThat(((FakeSmsSender) fake(profile("dev"), true)).revealsMessage()).isTrue();
    }

    @Test
    @DisplayName("the send is asynchronous unless a test asks otherwise")
    void theExecutorIsAsyncByDefault() {

        /*
         * The property exists so the suite can read a message immediately after
         * a request instead of polling. It must not be what production runs: a
         * provider call on the request thread would overrun the constant-time
         * floor on /password/forgot for registered numbers only, which is a
         * wider enumeration channel than the one the floor was added to close.
         */
        assertThat(config.smsExecutor(true)).isNotInstanceOf(SyncTaskExecutor.class);
        assertThat(config.smsExecutor(false)).isInstanceOf(SyncTaskExecutor.class);
    }
}
