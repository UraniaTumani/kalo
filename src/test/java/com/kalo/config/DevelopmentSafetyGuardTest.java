package com.kalo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard that stands between a forgotten profile and a production database
 * signed with a key that is committed to this repository. Worth proving rather
 * than assuming.
 */
@DisplayName("Development safety guard")
class DevelopmentSafetyGuardTest {

    private static final String DEV_SECRET =
            "ZGV2LW9ubHktc2VjcmV0LWZvci1sb2NhbC1rYWxvLWJhY2tlbmQtZGV2ZWxvcG1lbnQ=";

    private static final String REAL_SECRET = "a-private-value-supplied-by-the-environment";

    private static final String LOCAL_DB = "jdbc:postgresql://localhost:5432/kalo_db";
    private static final String REMOTE_DB = "jdbc:postgresql://db.production.internal:5432/kalo";

    private final DevelopmentSafetyGuard guard = new DevelopmentSafetyGuard(null);

    @Test
    @DisplayName("the exact failure it exists to prevent: dev secret, no profile, real database")
    void rejectsDevSecretAgainstProductionDatabase() {

        assertThatThrownBy(() -> guard.check(DEV_SECRET, REMOTE_DB, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start");
    }

    @Test
    @DisplayName("the dev secret is refused without the dev profile even on a local database")
    void rejectsDevSecretWithoutDevProfile() {

        assertThatThrownBy(() -> guard.check(DEV_SECRET, LOCAL_DB, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("committed to source control");
    }

    @Test
    @DisplayName("seeding demo accounts into a non-local database is refused")
    void rejectsSeedingAgainstRemoteDatabase() {

        assertThatThrownBy(() -> guard.check(REAL_SECRET, REMOTE_DB, true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seeding");
    }

    @Test
    @DisplayName("normal local development is allowed")
    void allowsLocalDevelopment() {

        assertThatCode(() -> guard.check(DEV_SECRET, LOCAL_DB, true, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("normal production is allowed")
    void allowsProduction() {

        assertThatCode(() -> guard.check(REAL_SECRET, REMOTE_DB, false, false))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://127.0.0.1:5432/kalo_db",
            "jdbc:postgresql://postgres:5432/kalo_db",
            "jdbc:postgresql://host.docker.internal:5432/kalo_db",
    })
    @DisplayName("container and loopback hosts count as local")
    void recognisesLocalHosts(String url) {
        assertThatCode(() -> guard.check(DEV_SECRET, url, true, true)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://10.0.0.5:5432/kalo",
            "jdbc:postgresql://kalo.abc123.eu-central-1.rds.amazonaws.com:5432/kalo",
    })
    @DisplayName("anything else counts as remote")
    void treatsEverythingElseAsRemote(String url) {
        assertThatThrownBy(() -> guard.check(REAL_SECRET, url, false, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an unreadable datasource url fails closed, not open")
    void unparseableUrlIsTreatedAsRemote() {

        assertThatThrownBy(() -> guard.check(REAL_SECRET, "", false, true))
                .isInstanceOf(IllegalStateException.class);
    }
}
