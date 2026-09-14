package com.kalo.config;

import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * What the deployment publishes to the outside world.
 *
 * Everything here is a default that only matters when nobody remembers to check
 * it. An API doc page left switched on hands an attacker a map of every
 * endpoint; a CORS wildcard lets any site spend a signed-in user's token.
 */
@DisplayName("Published API surface")
class ApiSurfaceIntegrationTest extends AbstractIntegrationTest {

    /*
     * Swagger deliberately has no behavioural test here. Asserting it through
     * MockMvc needs springdoc.api-docs.enabled=false as a @TestPropertySource,
     * because the test profile's application.properties shadows production's
     * and leaves springdoc running -- and that second property set builds a
     * second Spring context, which cost this class six minutes. What it proved
     * was that springdoc honours its own flag, which is springdoc's business.
     * The fact that matters to KALO is that production ships the flag off, and
     * ProductionConfigurationTest asserts exactly that, instantly.
     */

    @Nested
    @DisplayName("CORS")
    class Cors {

        @Value("${app.cors.allowed-origins:}")
        String configuredOrigins;

        /**
         * The default is same-origin: the API and the built frontend are served
         * together, so no cross-origin access is needed and none is granted.
         */
        @Test
        @DisplayName("no origins are allowed by default")
        void defaultsToSameOrigin() {

            assertThat(configuredOrigins).isEmpty();
        }

        @Test
        @DisplayName("a stranger's origin gets no allow header")
        void strangerOriginIsNotAllowed() throws Exception {

            mockMvc.perform(
                            options("/api/v1/auth/login")
                                    .header("Origin", "https://evil.example")
                                    .header("Access-Control-Request-Method", "POST")
                    )
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        /**
         * The guard that matters most. A wildcard origin combined with a bearer
         * token is the classic way a token leaks to any page a user visits, so
         * the application refuses to start rather than serving with one.
         */
        @Test
        @DisplayName("a wildcard origin refuses to start the application")
        void wildcardOriginIsRefused() {

            CorsConfig config = new CorsConfig();

            org.springframework.test.util.ReflectionTestUtils
                    .setField(config, "allowedOrigins", "https://app.kalo.al,*");

            assertThatThrownBy(config::corsConfigurationSource)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wildcard");
        }

        @Test
        @DisplayName("an exact origin is accepted, and only for the API")
        void exactOriginIsAccepted() {

            CorsConfig config = new CorsConfig();

            org.springframework.test.util.ReflectionTestUtils
                    .setField(config, "allowedOrigins", "https://app.kalo.al");

            var source = config.corsConfigurationSource();

            var request = new org.springframework.mock.web.MockHttpServletRequest(
                    "GET", "/api/v1/rides/current"
            );

            var configuration = source.getCorsConfiguration(request);

            assertThat(configuration).isNotNull();
            assertThat(configuration.getAllowedOrigins())
                    .containsExactly("https://app.kalo.al");

            /* A bearer-token API must never also allow credentials. */
            assertThat(configuration.getAllowCredentials()).isNotEqualTo(Boolean.TRUE);

            var outsideApi = new org.springframework.mock.web.MockHttpServletRequest(
                    "GET", "/actuator/health"
            );

            assertThat(source.getCorsConfiguration(outsideApi)).isNull();
        }
    }
}
