package com.kalo.support;

import com.jayway.jsonpath.JsonPath;
import com.kalo.KaloBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresApplicationRestoreRehearsalIT extends AbstractIntegrationTest {

    @Test
    void restoredSchemaAndCustomerCanBeUsedByASecondApplication() throws Exception {
        var customer = fixtures.customer();
        // Also confirm the fixture works through the source application before the dump.
        tokenFor(customer);
        long sourceChanges = jdbcTemplate.queryForObject("SELECT count(*) FROM databasechangelog", Long.class);
        assertThat(sourceChanges).isPositive();

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "restore_" + suffix;
        String archive = "/tmp/kalo-" + suffix + ".dump";
        String user = POSTGRES.getUsername();
        postgres("createdb", "-U", user, database);
        try {
            postgres("pg_dump", "-U", user, "-Fc", "-f", archive, POSTGRES.getDatabaseName());
            postgres("pg_restore", "-U", user, "--exit-on-error", "--single-transaction",
                    "-d", database, archive);

            // Disable Liquibase on the target so startup cannot repair a missing schema.
            try (var restored = new SpringApplicationBuilder(KaloBackendApplication.class)
                    .profiles("test")
                    .run("--server.address=127.0.0.1", "--server.port=0",
                            "--spring.datasource.url=" + POSTGRES.getJdbcUrl().replace(
                                    "/" + POSTGRES.getDatabaseName(), "/" + database),
                            "--spring.datasource.username=" + user,
                            "--spring.datasource.password=" + POSTGRES.getPassword(),
                            "--spring.liquibase.enabled=false",
                            "--spring.jpa.hibernate.ddl-auto=validate",
                            "--app.documents.provider=local")) {
                var restoredJdbc = restored.getBean(JdbcTemplate.class);
                assertThat(restoredJdbc.queryForObject("SELECT current_database()", String.class))
                        .isEqualTo(database);
                assertThat(restoredJdbc.queryForObject("SELECT count(*) FROM databasechangelog", Long.class))
                        .isEqualTo(sourceChanges);

                int port = restored.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                URI base = URI.create("http://127.0.0.1:" + port);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                var login = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of(
                                        "phone", customer.getPhone(), "password", TestDataFactory.PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(login.statusCode()).isEqualTo(200);
                String token = JsonPath.read(login.body(), "$.accessToken");
                assertThat(token).isNotBlank();

                var profile = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/me"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", bearer(token)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(profile.statusCode()).isEqualTo(200);
                assertThat(JsonPath.parse(profile.body()).read("$.id", Long.class)).isEqualTo(customer.getId());
                assertThat(JsonPath.parse(profile.body()).read("$.phone", String.class)).isEqualTo(customer.getPhone());
            }
        } finally {
            try {
                postgres("dropdb", "-U", user, database);
            } finally {
                postgres("rm", "-f", archive);
            }
        }
    }

    private static void postgres(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as("%s: %s", command[0], result.getStderr()).isZero();
    }
}
