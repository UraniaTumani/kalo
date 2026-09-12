package com.kalo;

import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the application context builds and Liquibase brings the schema up
 * against a real PostgreSQL.
 *
 * Extends the shared base so it uses the same container and cached context as
 * every other test; on its own it had no datasource at all and could never
 * start.
 */
@DisplayName("Application context")
class KaloBackendApplicationTests extends AbstractIntegrationTest {

    @Test
    @DisplayName("loads with migrations applied and schema validation passing")
    void contextLoads() {
    }
}
