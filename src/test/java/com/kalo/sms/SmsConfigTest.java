package com.kalo.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider switch, tested without a Spring context.
 *
 * Worth its own test because both branches are about what happens when somebody
 * gets the configuration wrong, and neither is reachable from an integration
 * test: the suite runs with one provider and one profile.
 */
@DisplayName("SMS configuration")
class SmsConfigTest {

    private final SmsConfig config = new SmsConfig();

    private static Environment profile(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    @DisplayName("an unknown provider fails at startup rather than falling back")
    void anUnknownProviderRefusesToStart() {

        /*
         * The failure mode this exists for: SMS_PROVIDER=infobop, a deployment
         * that starts normally, and codes that silently stop being delivered
         * while everything looks configured. Better to not start.
         */
        assertThatThrownBy(() -> config.smsSender(profile("prod"), "twilio", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown app.sms.provider")
                .hasMessageContaining("SmsSender");
    }

    @Test
    @DisplayName("the fake provider is chosen by the default value")
    void theDefaultIsTheFake() {

        assertThat(config.smsSender(profile("dev"), "log", true))
                .isInstanceOf(FakeSmsSender.class);
    }

    @Test
    @DisplayName("the message body is never logged outside development")
    void theBodyIsNotLoggedInProduction() {

        /*
         * Gated on the profile as well as the property, so setting
         * SMS_LOG_MESSAGE=true against a real deployment does not start writing
         * live one-time codes into its logs — and from there into every
         * aggregator and pasted support ticket they reach.
         *
         * Asserted through behaviour rather than a getter: the fake logs the body
         * only when it was built with revealing on, so what this really pins is
         * that SmsConfig does not hand it that flag outside dev.
         */
        FakeSmsSender production =
                (FakeSmsSender) config.smsSender(profile("prod"), "log", true);

        assertThat(production.revealsMessage()).isFalse();

        FakeSmsSender development =
                (FakeSmsSender) config.smsSender(profile("dev"), "log", true);

        assertThat(development.revealsMessage()).isTrue();
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
