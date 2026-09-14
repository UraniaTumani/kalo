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
}
