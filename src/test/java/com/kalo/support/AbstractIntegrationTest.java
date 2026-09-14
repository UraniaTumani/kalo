package com.kalo.support;

import com.jayway.jsonpath.JsonPath;
import com.kalo.user.entity.User;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for every integration test.
 *
 * Runs against a real PostgreSQL container rather than an in-memory database,
 * because the schema is owned by Liquibase and several invariants are enforced
 * by PostgreSQL itself — the partial unique indexes in migration 017 and the
 * check constraint in 018 simply do not exist on H2, so a test that passed
 * there would prove nothing about production.
 *
 * The container is started once for the JVM and shared by every test class.
 * Spring caches the application context alongside it, so the whole suite pays
 * for one container start and one context refresh.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /*
     * Started here rather than through @Testcontainers/@Container so JUnit does
     * not stop it between classes; the JVM exiting is what tears it down.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TestDataFactory fixtures;

    /**
     * Protected so a test can set up a state the API cannot reach — a refresh
     * token already past its expiry, say, which no test can produce by waiting
     * thirty days.
     */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Empties every application table before each test.
     *
     * Rolling back a transaction would not work here: the services under test
     * manage their own transactions and take pessimistic locks, and the ride
     * flow spans several requests. Truncating is blunt but gives each test a
     * genuinely clean database, which keeps them order-independent.
     *
     * Liquibase's own tables are left alone so migrations are not re-applied.
     */
    @BeforeEach
    void resetDatabase() {

        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename NOT LIKE 'databasechangelog%'
                """,
                String.class
        );

        if (tables.isEmpty()) {
            return;
        }

        jdbcTemplate.execute(
                "TRUNCATE TABLE "
                        + String.join(", ", tables)
                        + " RESTART IDENTITY CASCADE"
        );
    }

    /* ----------------------------------------------------------- helpers */

    /**
     * Signs in through the real endpoint rather than minting a token directly,
     * so every authenticated test also exercises login and the filter chain.
     */
    protected String login(String phone, String password) throws Exception {

        String body = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"phone":"%s","password":"%s"}
                                        """.formatted(phone, password))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.accessToken");
    }

    protected String tokenFor(User user) throws Exception {
        return login(user.getPhone(), TestDataFactory.PASSWORD);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
