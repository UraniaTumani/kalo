package com.kalo.location;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.location.dto.UpdateDriverLocationRequest;
import com.kalo.location.service.DriverLocationWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DriverSelfLocationIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverLocationWriter writer;
    @Autowired TransactionTemplate transactions;
    private record Account(User user, Long driverId) {}
    private Account account() {
        var user = fixtures.user(UserRole.DRIVER, UserStatus.ACTIVE);
        var driver = fixtures.driver(fixtures.approvedCompany(), DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        jdbcTemplate.update("UPDATE drivers SET user_id=?, phone=? WHERE id=?", user.getId(), user.getPhone(), driver.getId());
        return new Account(user, driver.getId());
    }
    @Test void updateUsesOwnIdentityEvenWhenAnotherIdIsSupplied() throws Exception {
        var own = account(); var other = account();
        mockMvc.perform(put("/api/v1/driver/me/location").param("driverId", other.driverId().toString())
            .header("Authorization", bearer(tokenFor(own.user()))).contentType(APPLICATION_JSON)
            .content("{\"latitude\":41.3,\"longitude\":19.8}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.driverId").value(own.driverId().intValue()));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations WHERE driver_id=?", Long.class, own.driverId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations WHERE driver_id=?", Long.class, other.driverId())).isZero();
    }
    @Test void validatesCoordinates() throws Exception {
        var own = account();
        mockMvc.perform(put("/api/v1/driver/me/location").header("Authorization", bearer(tokenFor(own.user())))
            .contentType(APPLICATION_JSON).content("{\"latitude\":91,\"longitude\":19}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations", Long.class)).isZero();
    }
    @Test void rejectsAnonymousAndOtherRoles() throws Exception {
        var body = "{\"latitude\":41,\"longitude\":19}";
        mockMvc.perform(put("/api/v1/driver/me/location").contentType(APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        for (var role : new UserRole[]{UserRole.PARTNER, UserRole.CUSTOMER, UserRole.ADMIN}) {
            var user = fixtures.user(role, UserStatus.ACTIVE);
            mockMvc.perform(put("/api/v1/driver/me/location").header("Authorization", bearer(tokenFor(user)))
                .contentType(APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
    }
    @Test void revokedBindingCannotWrite() throws Exception {
        var own = account(); var token = tokenFor(own.user());
        jdbcTemplate.update("UPDATE drivers SET user_id=NULL WHERE id=?", own.driverId());
        mockMvc.perform(put("/api/v1/driver/me/location").header("Authorization", bearer(token))
            .contentType(APPLICATION_JSON).content("{\"latitude\":41,\"longitude\":19}"))
            .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations", Long.class)).isZero();
    }
    @Test void simultaneousInitialWritesCreateOneRow() throws Exception {
        var own = account(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); return transactions.execute(s -> writer.write(own.driverId(), new UpdateDriverLocationRequest(41.0, 19.0))); });
            var two = pool.submit(() -> { start.await(); return transactions.execute(s -> writer.write(own.driverId(), new UpdateDriverLocationRequest(42.0, 20.0))); });
            start.countDown(); assertThat(one.get(15, TimeUnit.SECONDS)).isNotNull(); assertThat(two.get(15, TimeUnit.SECONDS)).isNotNull();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations WHERE driver_id=?", Long.class, own.driverId())).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void suspensionCommittedWhileLocationWaitsIsRechecked() throws Exception {
        var own = account();
        var token = tokenFor(own.user());
        Long companyId = jdbcTemplate.queryForObject("SELECT company_id FROM drivers WHERE id=?", Long.class, own.driverId());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = transactions.execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM taxi_companies WHERE id=? FOR UPDATE", Long.class, companyId);
                var pending = pool.submit(() -> mockMvc.perform(put("/api/v1/driver/me/location")
                    .header("Authorization", bearer(token)).contentType(APPLICATION_JSON)
                    .content("{\"latitude\":41,\"longitude\":19}")).andReturn().getResponse().getStatus());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    // PostgreSQL caches statistics within a transaction unless cleared.
                    jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
                    if (pending.isDone()) {
                        throw new IllegalStateException("GPS request ended before waiting for the lock");
                    }
                    Long count = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM taxi_companies WHERE id =%FOR UPDATE%'
                        """, Long.class);
                    if (count != null && count > 0) { waiting = true; break; }
                    try { Thread.sleep(50); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(ex);
                    }
                }
                if (!waiting) throw new IllegalStateException("GPS request did not reach the company lock");
                jdbcTemplate.update("UPDATE taxi_companies SET status='SUSPENDED' WHERE id=?", companyId);
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations", Long.class)).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void userSuspendedWhileLocationWaitsIsRechecked() throws Exception {
        var own = account();
        var token = tokenFor(own.user());
        Long userId = own.user().getId();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = transactions.execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
                var pending = pool.submit(() -> mockMvc.perform(put("/api/v1/driver/me/location")
                    .header("Authorization", bearer(token)).contentType(APPLICATION_JSON)
                    .content("{\"latitude\":41,\"longitude\":19}")).andReturn().getResponse().getStatus());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    // PostgreSQL caches statistics within a transaction unless cleared.
                    jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
                    if (pending.isDone()) {
                        throw new IllegalStateException("GPS request ended before waiting for the lock");
                    }
                    Long count = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM users WHERE id =%FOR UPDATE%'
                        """, Long.class);
                    if (count != null && count > 0) { waiting = true; break; }
                    try { Thread.sleep(50); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(ex);
                    }
                }
                if (!waiting) throw new IllegalStateException("GPS request did not reach the user lock");
                jdbcTemplate.update("UPDATE users SET status='SUSPENDED' WHERE id=?", userId);
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations", Long.class)).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void driverDisabledWhileLocationWaitsIsRechecked() throws Exception {
        var own = account();
        var token = tokenFor(own.user());
        Long driverId = own.driverId();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = transactions.execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM drivers WHERE id=? FOR UPDATE", Long.class, driverId);
                var pending = pool.submit(() -> mockMvc.perform(put("/api/v1/driver/me/location")
                    .header("Authorization", bearer(token)).contentType(APPLICATION_JSON)
                    .content("{\"latitude\":41,\"longitude\":19}")).andReturn().getResponse().getStatus());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    // PostgreSQL caches statistics within a transaction unless cleared.
                    jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
                    if (pending.isDone()) {
                        throw new IllegalStateException("GPS request ended before waiting for the lock");
                    }
                    Long count = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM drivers WHERE id =%FOR UPDATE%'
                        """, Long.class);
                    if (count != null && count > 0) { waiting = true; break; }
                    try { Thread.sleep(50); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(ex);
                    }
                }
                if (!waiting) throw new IllegalStateException("GPS request did not reach the driver lock");
                jdbcTemplate.update("UPDATE drivers SET status='INACTIVE' WHERE id=?", driverId);
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_locations", Long.class)).isZero();
        } finally { pool.shutdownNow(); }
    }

}
