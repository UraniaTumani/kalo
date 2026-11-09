package com.kalo.support;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiquibaseRollbackRehearsalIT {

    private static final String CHANGESET = "032-fleet-document-review-audit";
    private static final String FILE = "db/changelog/changes/032-fleet-document-review-audit.yaml";

    @Test
    void rollsBackAndReappliesDocumentAuditWithoutRemovingTheDocument() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            try (var connection = dataSource.getConnection();
                 var resources = new ClassLoaderResourceAccessor();
                 var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                         resources, new JdbcConnection(connection))) {
                var contexts = new Contexts();
                var labels = new LabelExpression();
                liquibase.update(contexts, labels);

                // Rollback by count is safe here only if the intended changeset is last.
                assertThat(jdbc.queryForObject("""
                        SELECT concat(id, ':', author, ':', filename)
                        FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1
                        """, String.class)).isEqualTo(CHANGESET + ":kalo:" + FILE);
                long reviewer = jdbc.queryForObject("""
                        INSERT INTO users (first_name, last_name, phone, password_hash, role, status, created_at, updated_at)
                        VALUES ('Rollback', 'Fixture', '+355690000001', 'fixture', 'ADMIN', 'ACTIVE', now(), now())
                        RETURNING id
                        """, Long.class);
                long document = jdbc.queryForObject("""
                        INSERT INTO documents (owner_type, owner_id, document_type, file_url, verification_status,
                                               created_at, updated_at, reviewed_by_user_id, reviewed_at)
                        VALUES ('VEHICLE', 1, 'VEHICLE_REGISTRATION', 'local:fixture.pdf', 'APPROVED',
                                now(), now(), ?, now())
                        RETURNING id
                        """, Long.class, reviewer);
                assertReviewProtected(jdbc, document);

                liquibase.rollback(1, null, contexts, labels);
                assertThat(historyRows(jdbc)).isZero();
                assertThat(auditColumns(jdbc)).isZero();
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_trigger
                        WHERE tgrelid = 'public.documents'::regclass AND tgname = 'trg_protect_document_review_audit'
                        """, Long.class)).isZero();
                assertThat(jdbc.queryForObject("""
                        SELECT file_url FROM documents WHERE id = ?
                        """, String.class, document)).isEqualTo("local:fixture.pdf");

                liquibase.update(contexts, labels);
                assertThat(historyRows(jdbc)).isEqualTo(1L);
                assertThat(auditColumns(jdbc)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("""
                        SELECT verification_status = 'APPROVED'
                               AND reviewed_at IS NULL AND reviewed_by_user_id IS NULL
                        FROM documents WHERE id = ?
                        """, Boolean.class, document)).isTrue();
                assertThat(jdbc.update("""
                        UPDATE documents SET reviewed_by_user_id = ?, reviewed_at = now() WHERE id = ?
                        """, reviewer, document)).isEqualTo(1);
                assertReviewProtected(jdbc, document);
            }
        }
    }

    private static void assertReviewProtected(JdbcTemplate jdbc, long document) {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE documents SET reviewed_at = reviewed_at + interval '1 second' WHERE id = ?", document))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("Recorded document review cannot be changed");
    }

    private static long historyRows(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM databasechangelog WHERE id = ? AND author = 'kalo' AND filename = ?
                """, Long.class, CHANGESET, FILE);
    }

    private static long auditColumns(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'documents'
                  AND column_name IN ('reviewed_at', 'reviewed_by_user_id')
                """, Long.class);
    }
}
