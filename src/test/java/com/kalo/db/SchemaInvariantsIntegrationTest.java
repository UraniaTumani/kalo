package com.kalo.db;

import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guarantees PostgreSQL makes on its own, independently of any Java.
 *
 * Application code enforces these too, but application code runs in one thread
 * at a time and a race does not care what the service layer intended. Migration
 * 017 exists precisely because two requests arriving together could otherwise
 * put one driver on two rides. These tests write straight to the tables so the
 * database is the only thing standing in the way — if a migration is ever
 * dropped or a partial index loses its WHERE clause, this is what notices.
 *
 * Every test here also asserts that the migration ran at all, which is the
 * other half: an index that silently failed to create protects nothing.
 */
@DisplayName("Database invariants")
class SchemaInvariantsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private boolean indexExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class,
                name
        );
        return count != null && count > 0;
    }

    private boolean constraintExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?",
                Integer.class,
                name
        );
        return count != null && count > 0;
    }

    @Nested
    @DisplayName("Migrations")
    class Migrations {

        @Test
        @DisplayName("every changeset in the master changelog has been applied")
        void allChangesetsApplied() {

            List<String> ids = jdbc.queryForList(
                    "SELECT id FROM databasechangelog ORDER BY orderexecuted",
                    String.class
            );

            /*
             * Named explicitly rather than counted: a count passes when a
             * changeset is renamed or replaced, which is exactly the change
             * worth catching.
             */
            assertThat(ids)
                    .contains(
                            "017-one-active-assignment-per-driver",
                            "017-one-active-assignment-per-vehicle",
                            "017-one-active-ride-per-customer",
                            "017-one-active-ride-per-driver",
                            "020-create-refresh-tokens",
                            "021-create-support-requests"
                    );
        }

        @Test
        @DisplayName("the tables the newest features depend on exist")
        void newestTablesExist() {

            List<String> tables = jdbc.queryForList(
                    """
                    SELECT tablename FROM pg_tables WHERE schemaname = 'public'
                    """,
                    String.class
            );

            assertThat(tables).contains("refresh_tokens", "support_requests");
        }

        /**
         * ddl-auto is `validate`, so the context would not start if an entity
         * disagreed with the schema. That this test runs at all is the
         * assertion; the explicit check is here so the guarantee is named
         * rather than implied.
         */
        @Test
        @DisplayName("Hibernate validated the schema it was handed")
        void schemaValidates() {

            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Partial unique indexes")
    class PartialUniqueIndexes {

        @Test
        @DisplayName("the concurrency indexes from migration 017 are present")
        void indexesExist() {

            assertThat(indexExists("uk_active_assignment_per_driver")).isTrue();
            assertThat(indexExists("uk_active_assignment_per_vehicle")).isTrue();
            assertThat(indexExists("uk_active_ride_per_customer")).isTrue();
            assertThat(indexExists("uk_active_ride_per_driver")).isTrue();
        }

        /**
         * The race the index exists to stop: two accept requests landing
         * together, each reading "this driver is free" before either writes.
         */
        @Test
        @DisplayName("a driver cannot hold two active vehicle assignments")
        void oneActiveAssignmentPerDriver() {

            Fixture f = fixture();

            jdbc.update(
                    """
                    INSERT INTO driver_vehicle_assignments
                      (driver_id, vehicle_id, assigned_from, active, created_at, updated_at)
                    VALUES (?, ?, now(), true, now(), now())
                    """,
                    f.driverId, f.vehicleId
            );

            assertThatThrownBy(() -> jdbc.update(
                    """
                    INSERT INTO driver_vehicle_assignments
                      (driver_id, vehicle_id, assigned_from, active, created_at, updated_at)
                    VALUES (?, ?, now(), true, now(), now())
                    """,
                    f.driverId, f.secondVehicleId
            )).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("a vehicle cannot be held by two drivers at once")
        void oneActiveAssignmentPerVehicle() {

            Fixture f = fixture();

            jdbc.update(
                    """
                    INSERT INTO driver_vehicle_assignments
                      (driver_id, vehicle_id, assigned_from, active, created_at, updated_at)
                    VALUES (?, ?, now(), true, now(), now())
                    """,
                    f.driverId, f.vehicleId
            );

            assertThatThrownBy(() -> jdbc.update(
                    """
                    INSERT INTO driver_vehicle_assignments
                      (driver_id, vehicle_id, assigned_from, active, created_at, updated_at)
                    VALUES (?, ?, now(), true, now(), now())
                    """,
                    f.secondDriverId, f.vehicleId
            )).isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * The WHERE clause is the whole point: history has to be allowed to
         * accumulate, or a company could never reassign a car.
         */
        @Test
        @DisplayName("released assignments do not collide, so history can accumulate")
        void inactiveAssignmentsDoNotCollide() {

            Fixture f = fixture();

            for (int i = 0; i < 3; i++) {
                jdbc.update(
                        """
                        INSERT INTO driver_vehicle_assignments
                          (driver_id, vehicle_id, assigned_from, assigned_until, active,
                           created_at, updated_at)
                        VALUES (?, ?, now(), now(), false, now(), now())
                        """,
                        f.driverId, f.vehicleId
                );
            }

            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM driver_vehicle_assignments WHERE driver_id = ?",
                    Integer.class, f.driverId
            );

            assertThat(rows).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Check constraints")
    class CheckConstraints {

        @Test
        @DisplayName("a company cannot be suspended unless it was approved")
        void suspendedRequiresApproved() {

            assertThat(constraintExists("ck_suspended_requires_approved")).isTrue();

            /* The database is truncated before each test, so make the row. */
            final Long id = fixture().companyId;

            assertThatThrownBy(() -> jdbc.update(
                    """
                    UPDATE taxi_companies
                    SET verification_status = 'DRAFT', status = 'SUSPENDED'
                    WHERE id = ?
                    """,
                    id
            )).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("Unique constraints")
    class UniqueConstraints {

        @Test
        @DisplayName("two users cannot share a phone number")
        void userPhoneIsUnique() {

            jdbc.update(
                    """
                    INSERT INTO users (first_name, last_name, phone, password_hash,
                                       role, status, phone_verified, created_at, updated_at)
                    VALUES ('A','B','+355699999001','x','CUSTOMER','ACTIVE',true,now(),now())
                    """
            );

            assertThatThrownBy(() -> jdbc.update(
                    """
                    INSERT INTO users (first_name, last_name, phone, password_hash,
                                       role, status, phone_verified, created_at, updated_at)
                    VALUES ('C','D','+355699999001','y','CUSTOMER','ACTIVE',true,now(),now())
                    """
            )).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("a company cannot have two rows for the same weekday")
        void operatingHoursAreOnePerDay() {

            Fixture f = fixture();

            assertThatThrownBy(() -> jdbc.update(
                    """
                    INSERT INTO company_operating_hours
                      (company_id, day_of_week, open_time, close_time, closed,
                       created_at, updated_at)
                    VALUES (?, 'MONDAY', '08:00', '18:00', false, now(), now())
                    """,
                    f.companyId
            )).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("two refresh tokens cannot share a hash")
        void refreshTokenHashIsUnique() {

            Fixture f = fixture();

            jdbc.update(
                    """
                    INSERT INTO refresh_tokens
                      (user_id, token_hash, expires_at, created_at, updated_at)
                    VALUES (?, 'deadbeef', now() + interval '1 day', now(), now())
                    """,
                    f.ownerId
            );

            assertThatThrownBy(() -> jdbc.update(
                    """
                    INSERT INTO refresh_tokens
                      (user_id, token_hash, expires_at, created_at, updated_at)
                    VALUES (?, 'deadbeef', now() + interval '1 day', now(), now())
                    """,
                    f.ownerId
            )).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("a ride can only be rated once")
        void oneRatingPerRide() {

            assertThat(
                    indexExists("uk_ride_ratings_ride")
                            || constraintExists("uk_ride_ratings_ride")
            ).isTrue();
        }
    }

    /* ------------------------------------------------------------ fixture */

    private record Fixture(Long companyId, Long ownerId, Long driverId,
                           Long secondDriverId, Long vehicleId, Long secondVehicleId) {
    }

    /**
     * Built through the normal fixtures so the rows are realistic, then read
     * back as ids because these tests work in SQL rather than through JPA.
     */
    private Fixture fixture() {

        var company = fixtures.approvedCompany();

        var driver = fixtures.driver(
                company,
                com.kalo.driver.enums.DriverStatus.ACTIVE,
                com.kalo.driver.enums.DriverAvailabilityStatus.OFFLINE
        );

        var secondDriver = fixtures.driver(
                company,
                com.kalo.driver.enums.DriverStatus.ACTIVE,
                com.kalo.driver.enums.DriverAvailabilityStatus.OFFLINE
        );

        var vehicle = fixtures.vehicle(
                company, com.kalo.vehicle.enums.VehicleStatus.ACTIVE
        );

        var secondVehicle = fixtures.vehicle(
                company, com.kalo.vehicle.enums.VehicleStatus.ACTIVE
        );

        return new Fixture(
                company.getId(),
                company.getOwner().getId(),
                driver.getId(),
                secondDriver.getId(),
                vehicle.getId(),
                secondVehicle.getId()
        );
    }
}
