package com.kalo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    /**
     * Every switch the test profile turns off, and what it costs in production.
     *
     * A list rather than three assertions, because the way this test failed once
     * already is instructive: F36 added a third such switch and nobody thought to
     * come back here, so licence enforcement's visible half shipped with no guard
     * at all (F43). Three separate assertions invite that; one list that a reader
     * can see is incomplete does not.
     *
     * The consequence is spelled out per switch rather than left as "must not be
     * false", because whoever trips this needs to know what they turned off, and
     * a bare property name does not tell them.
     *
     * ADDING A SWITCH: if you set something to false in
     * src/test/resources/application.properties so the suite stays deterministic,
     * add it here too.
     *
     * A cross-check that read the test profile and failed when this list was
     * shorter was considered and rejected. It would be wrong for the first flag
     * that is legitimately false in both profiles — a feature genuinely off in
     * production — and covering that needs an exemption list that nothing yet
     * justifies. One obvious place to look beats machinery that has to be argued
     * with.
     */
    private static final Map<String, String> TEST_ONLY_SWITCHES = Map.of(
            "app.rate-limit.enabled",
            "rate limiting must not be disabled in production: an unauthenticated "
                    + "endpoint that sends SMS is an endpoint that spends money",

            "app.ride.timeout-sweep.enabled",
            "the ride timeout sweep must not be disabled in production: with it off "
                    + "everywhere, a company that never answers keeps the ride forever",

            "app.driver.license-sweep.enabled",
            "the driver licence sweep must not be disabled in production: a driver "
                    + "whose licence lapsed would keep showing as ONLINE to their own "
                    + "partner while never being offered a ride"
    );

    @Test
    @DisplayName("the switches the test profile disables are not disabled in production")
    void testOnlySwitchesStayOutOfProduction() throws IOException {

        Properties p = production();

        TEST_ONLY_SWITCHES.forEach((property, consequence) ->
                assertThat(p.getProperty(property))
                        .as(consequence)
                        .isNotEqualTo("false")
        );
    }

    /**
     * Pins the shipped response window, which the test profile does not.
     *
     * The test profile keeps 60 on purpose and RideTimeoutIntegrationTest mirrors
     * that value, so no test in the suite would notice if production drifted back
     * to a minute — which is exactly the change F33 was raised to make. Asserted
     * on the file as shipped rather than on an injected property, because what
     * matters is the default a deployment gets when it sets nothing.
     */
    @Test
    @DisplayName("the company response window ships at two minutes")
    void companyResponseWindowIsTwoMinutes() throws IOException {

        assertThat(production().getProperty("app.ride.company-response-timeout-seconds"))
                .as("F33 raised this to 120; a minute is not long enough for a "
                        + "dispatcher who has to come back to the screen first")
                .isEqualTo("120");
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
