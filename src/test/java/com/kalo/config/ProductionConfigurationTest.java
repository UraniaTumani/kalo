package com.kalo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the settings the production deployment actually ships with.
 *
 * This test exists because nothing else could catch a change here.
 * `src/test/resources/application.properties` shadows the production file
 * completely — Spring resolves one `classpath:application.properties` and the
 * test classpath comes first — so no integration test in this suite ever loads
 * the real configuration. ObservabilityIntegrationTest looks like it covers
 * some of this, but it re-declares the same values in a @TestPropertySource,
 * which means it verifies the behaviour of those values and not that production
 * sets them. Deleting `management.endpoint.health.roles=ADMIN` from the
 * production file would leave that test green.
 *
 * So this reads the file off disk and checks the settings whose absence is a
 * security problem rather than a bug.
 */
@DisplayName("Production configuration")
class ProductionConfigurationTest {

    private static final Path FILE =
            Path.of("src/main/resources/application.properties");

    private static Properties production() throws IOException {

        Properties properties = new Properties();

        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        }

        return properties;
    }

    @Test
    @DisplayName("API documentation is off unless a deployment opts in")
    void swaggerDefaultsToOff() throws IOException {

        Properties p = production();

        /*
         * The placeholder default is the part that matters: `${SWAGGER_ENABLED:false}`
         * means a deployment that sets nothing publishes nothing.
         */
        assertThat(p.getProperty("springdoc.api-docs.enabled"))
                .isEqualTo("${SWAGGER_ENABLED:false}");

        assertThat(p.getProperty("springdoc.swagger-ui.enabled"))
                .isEqualTo("${SWAGGER_ENABLED:false}");
    }

    @Test
    @DisplayName("health detail is shown only to an authorized admin")
    void healthDetailIsRestricted() throws IOException {

        Properties p = production();

        assertThat(p.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("when_authorized");

        assertThat(p.getProperty("management.endpoint.health.roles"))
                .isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("only the four intended actuator endpoints are exposed")
    void actuatorExposureIsNarrow() throws IOException {

        String exposed = production()
                .getProperty("management.endpoints.web.exposure.include");

        assertThat(exposed).isEqualTo("health,info,metrics,prometheus");

        /*
         * Named individually because these are the ones that hand over
         * credentials, configuration or memory contents.
         */
        assertThat(exposed).doesNotContain("env");
        assertThat(exposed).doesNotContain("heapdump");
        assertThat(exposed).doesNotContain("loggers");
        assertThat(exposed).doesNotContain("threaddump");
        assertThat(exposed).doesNotContain("*");
    }

    @Test
    @DisplayName("CORS is same-origin unless a deployment names exact origins")
    void corsDefaultsToSameOrigin() throws IOException {

        assertThat(production().getProperty("app.cors.allowed-origins"))
                .isEqualTo("${CORS_ALLOWED_ORIGINS:}");
    }

    @Test
    @DisplayName("the JWT secret has no default, so a deployment must supply one")
    void jwtSecretHasNoFallback() throws IOException {

        String secret = production().getProperty("app.jwt.secret");

        /*
         * No `:default` inside the placeholder. A fallback here would ship a
         * signing key in the repository, and every token in production could
         * then be forged from a public string.
         */
        assertThat(secret).isEqualTo("${JWT_SECRET}");
        assertThat(secret).doesNotContain(":");
    }

    @Test
    @DisplayName("the schema is validated, never generated")
    void hibernateNeverWritesTheSchema() throws IOException {

        Properties p = production();

        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        assertThat(p.getProperty("spring.liquibase.change-log"))
                .isEqualTo("classpath:db/changelog/db.changelog-master.yaml");
    }

    @Test
    @DisplayName("open-in-view is off, so a lazy load cannot happen in a view")
    void openInViewIsOff() throws IOException {

        assertThat(production().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    /**
     * The test profile turns these off so the suite is deterministic. That is
     * correct for tests and would be a hole in production, so this checks the
     * production file does not carry the same switches.
     */
    @Test
    @DisplayName("the switches the test profile disables are not disabled in production")
    void testOnlySwitchesStayOutOfProduction() throws IOException {

        Properties p = production();

        assertThat(p.getProperty("app.rate-limit.enabled"))
                .as("rate limiting must not be disabled in production")
                .isNotEqualTo("false");

        assertThat(p.getProperty("app.ride.timeout-sweep.enabled"))
                .as("the ride timeout sweep must not be disabled in production")
                .isNotEqualTo("false");
    }

    @Test
    @DisplayName("no credential is hardcoded outside a placeholder")
    void credentialsComeFromTheEnvironment() throws IOException {

        Properties p = production();

        for (String key : new String[]{
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "app.jwt.secret",
        }) {
            assertThat(p.getProperty(key))
                    .as("%s must be supplied by the environment", key)
                    .startsWith("${");
        }
    }

    @Test
    @DisplayName("the bootstrapped administrator is named after the current product")
    void bootstrapNameIsTheCurrentBrand() throws IOException {

        /*
         * This default is visible. The bootstrapped administrator's name shows
         * in the sidebar, so it is branding rather than an internal label —
         * and it went on saying KALO after the rebrand because nothing looked
         * at it. Anybody bootstrapping without setting the variable got an
         * administrator called "KALO Administrator".
         *
         * Here rather than in AdminCreationIntegrationTest for the reason this
         * whole file exists: the test properties shadow the production ones,
         * so an integration test asking Spring for this value is told whatever
         * the test file says, which proves nothing about what ships.
         */
        Properties p = production();

        assertThat(p.getProperty("app.admin.bootstrap.first-name"))
                .as("the default administrator name must not carry a retired brand")
                .isEqualTo("${ADMIN_BOOTSTRAP_FIRST_NAME:MR TAXI}");

        assertThat(p.getProperty("app.admin.bootstrap.last-name"))
                .isEqualTo("${ADMIN_BOOTSTRAP_LAST_NAME:Administrator}");
    }

    @Test
    @DisplayName("a live recovery code is never written to a production log")
    void smsBodyLoggingDefaultsToOff() throws IOException {

        Properties p = production();

        /*
         * The message body carries a one-time code in plain text, and a code in a
         * log file is a code in every aggregator, terminal scrollback and pasted
         * support ticket it reaches afterwards. The placeholder default is the
         * part that matters: a deployment that sets nothing logs nothing.
         *
         * SmsConfig also refuses to honour the flag outside the dev and test
         * profiles, so this is the outer of two guards rather than the only one —
         * but it is the one a reviewer reads, and the one that would be quietly
         * flipped by somebody debugging.
         */
        assertThat(p.getProperty("app.sms.log-message"))
                .as("the SMS body must not be logged unless a developer opts in")
                .isEqualTo("${SMS_LOG_MESSAGE:false}");
    }

    @Test
    @DisplayName("the SMS budget has a ceiling whatever the environment says")
    void smsHasADailyCeiling() throws IOException {

        Properties p = production();

        /*
         * An unauthenticated endpoint that sends SMS is an unauthenticated
         * endpoint that spends money, and SMS pumping — farming traffic to
         * expensive ranges through whatever form will send it — is a real
         * business. A missing default here would mean no ceiling at all, so the
         * value must be a placeholder *with* a fallback: the opposite of the rule
         * for JWT_SECRET, and for the opposite reason.
         */
        String limit = p.getProperty("app.sms.daily-limit");

        assertThat(limit).isEqualTo("${SMS_DAILY_LIMIT:500}");
        assertThat(limit).contains(":");
    }

    @Test
    @DisplayName("no Infobip credential is hardcoded, and none has a usable default")
    void infobipCredentialsComeFromTheEnvironment() throws IOException {

        Properties p = production();

        /*
         * Empty placeholders rather than absent properties, and the difference
         * matters. SmsConfig validates these only when app.sms.provider=infobip,
         * so an empty default is what lets the shipped configuration stay valid
         * for a deployment that has no provider — while a *non-empty* default
         * would be either a fabricated endpoint or, worse, a committed key.
         *
         * The base URL is in here with the credentials on purpose: Infobip
         * issues one host per account, so a default would be a guess that sends
         * a real deployment's messages somewhere that rejects its key.
         */
        for (String key : new String[]{
                "app.sms.infobip.base-url",
                "app.sms.infobip.api-key",
                "app.sms.infobip.sender",
        }) {
            String value = p.getProperty(key);

            assertThat(value)
                    .as("%s must come from the environment", key)
                    .isNotNull()
                    .startsWith("${")
                    .endsWith(":}");
        }
    }

    @Test
    @DisplayName("the shipped provider is the one that admits it sends nothing")
    void smsProviderDefaultsToTheFake() throws IOException {

        /*
         * There is no real provider yet, and this asserts the honest default
         * rather than a hopeful one. `log` sends nothing and warns on every send
         * outside development, so a deployment cannot quietly tell people to
         * check a phone that will never ring — recovery there goes through the
         * admin fallback until somebody configures a provider.
         *
         * When one is added this assertion should change deliberately, which is
         * the point of pinning it.
         */
        assertThat(production().getProperty("app.sms.provider"))
                .isEqualTo("${SMS_PROVIDER:log}");
    }

    @Test
    @DisplayName("no retired brand name is left in the shipped configuration")
    void noRetiredBrandInConfiguration() throws IOException {

        /*
         * Broader than the default above, and cheap: the configuration file is
         * small and any visible string in it should carry the current name.
         * Database names, the JDBC URL and the like are internal and are
         * allowed to keep saying kalo — the check is for the capitalised brand
         * as it would be shown to somebody.
         */
        String raw = Files.readString(FILE);

        assertThat(raw)
                .as("application.properties still contains the retired brand name")
                .doesNotContain("KALO");
    }
}
