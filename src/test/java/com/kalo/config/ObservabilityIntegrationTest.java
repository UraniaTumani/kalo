package com.kalo.config;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the outside world can learn about this deployment.
 *
 * Metrics are useful to whoever runs the system and useful to whoever is probing
 * it: endpoint names, traffic shape, error rates and the rate of rides going
 * unanswered. Health has to stay reachable for a load balancer, everything else
 * has to not be.
 */
/*
 * src/test/resources/application.properties shadows the main file rather than
 * adding to it, so the exposure list has to be restated here. It mirrors
 * application.properties deliberately — if that list changes, change this one
 * too, because these assertions cannot see it.
 *
 * What is genuinely under test is SecurityConfig, which is production code: the
 * rules deciding who may read metrics apply exactly as they do in a deployment.
 */
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.prometheus.metrics.export.enabled=true",
        "management.endpoint.health.show-details=when_authorized",
        "management.endpoint.health.roles=ADMIN",
})
@DisplayName("Observability endpoints")
class ObservabilityIntegrationTest extends AbstractIntegrationTest {

    @Nested
    @DisplayName("Health")
    class HealthEndpoint {

        @Test
        @DisplayName("is reachable without a token, because a load balancer has no token")
        void isPublic() throws Exception {

            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("gives an anonymous caller no detail")
        void hidesDetailFromAnonymous() throws Exception {

            /*
             * The sweep indicator reports how long rides have gone unswept. That
             * tells an operator something useful and tells everyone else when the
             * system is unattended.
             */
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
    }

    @Nested
    @DisplayName("Metrics")
    class Metrics {

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRefused() throws Exception {

            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a signed-in customer is refused")
        void customerIsRefused() throws Exception {

            // Being a user of the system is not a reason to see its internals.
            mockMvc.perform(
                            get("/actuator/prometheus")
                                    .header("Authorization", bearer(tokenFor(fixtures.customer())))
                    )
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a partner is refused")
        void partnerIsRefused() throws Exception {

            mockMvc.perform(
                            get("/actuator/metrics")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(
                                                    fixtures.approvedCompany().getOwner()
                                            ))
                                    )
                    )
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin can scrape them")
        void adminCanScrape() throws Exception {

            User admin = fixtures.admin();

            mockMvc.perform(
                            get("/actuator/prometheus")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_")));
        }

        @Test
        @DisplayName("the sweep freshness gauge is published")
        void publishesSweepGauge() throws Exception {

            User admin = fixtures.admin();

            /*
             * The signal that closes the gap left by making only one instance
             * sweep: if that instance stops, nothing errors and rides simply sit
             * in REQUESTED. This gauge is the only thing that would say so.
             */
            mockMvc.perform(
                            get("/actuator/metrics/kalo.ride.sweep.seconds.since.success")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the timed-out ride counter is published")
        void publishesTimedOutCounter() throws Exception {

            User admin = fixtures.admin();

            // A company that never answers makes no request and returns no error,
            // so HTTP metrics cannot see it. This counter can.
            mockMvc.perform(
                            get("/actuator/metrics/kalo.ride.timed.out")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("request metrics are collected, so a failing endpoint is visible")
        void collectsHttpMetrics() throws Exception {

            User admin = fixtures.admin();
            String token = tokenFor(admin);

            mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());

            mockMvc.perform(
                            get("/actuator/metrics/http.server.requests")
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Endpoints that stay closed")
    class ClosedEndpoints {

        @Test
        @DisplayName("the environment is not exposed, even to an admin")
        void envIsNotExposed() throws Exception {

            User admin = fixtures.admin();

            /*
             * It would print the JWT secret and the database password. It is not
             * in the exposure list, so it does not exist — asserted rather than
             * assumed, because adding one to that list is a one-word change.
             */
            mockMvc.perform(
                            get("/actuator/env")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("heap dump and loggers are not exposed")
        void otherSensitiveEndpointsAreClosed() throws Exception {

            User admin = fixtures.admin();
            String token = tokenFor(admin);

            for (String endpoint : new String[]{"heapdump", "threaddump", "loggers", "configprops"}) {
                mockMvc.perform(
                                get("/actuator/" + endpoint)
                                        .header("Authorization", bearer(token))
                        )
                        .andExpect(status().isNotFound());
            }
        }
    }
}
