package com.kalo.driver.controller;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DriverSelfAvailabilityIntegrationTest extends AbstractIntegrationTest {
    private record Account(User user, Long driverId) {}
    private Account account(boolean vehicle) {
        var user = fixtures.user(UserRole.DRIVER, UserStatus.ACTIVE);
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        jdbcTemplate.update("UPDATE drivers SET user_id=?, phone=? WHERE id=?", user.getId(), user.getPhone(), driver.getId());
        if (vehicle) fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
        return new Account(user, driver.getId());
    }
    private org.springframework.test.web.servlet.ResultActions change(Account a, String value) throws Exception {
        return mockMvc.perform(put("/api/v1/driver/me/availability")
                .header("Authorization", bearer(tokenFor(a.user())))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"" + value + "\"}"));
    }
    private String state(Account a) {
        return jdbcTemplate.queryForObject("SELECT availability_status FROM drivers WHERE id=?", String.class, a.driverId());
    }
    @Test void driverCanGoOnlineAndOffline() throws Exception {
        var a = account(true);
        change(a, "ONLINE").andExpect(status().isOk()).andExpect(jsonPath("$.availabilityStatus").value("ONLINE"));
        assertThat(state(a)).isEqualTo("ONLINE");
        change(a, "OFFLINE").andExpect(status().isOk());
        assertThat(state(a)).isEqualTo("OFFLINE");
    }
    @Test void onlineRequiresAssignmentAndValidLicense() throws Exception {
        var noVehicle = account(false);
        change(noVehicle, "ONLINE").andExpect(status().isBadRequest());
        assertThat(state(noVehicle)).isEqualTo("OFFLINE");
        var expired = account(true);
        jdbcTemplate.update("UPDATE drivers SET license_expiry_date=? WHERE id=?", java.sql.Date.valueOf(com.kalo.common.util.LicenseValidity.today().minusDays(1)), expired.driverId());
        change(expired, "ONLINE").andExpect(status().isBadRequest());
        assertThat(state(expired)).isEqualTo("OFFLINE");
    }
    @Test void invalidVehicleDatesRefuseOnlineButAllowOffline() throws Exception {
        for (String column : new String[]{"registration_expiry_date", "insurance_expiry_date", "technical_inspection_expiry_date"}) {
            for (java.time.LocalDate invalid : new java.time.LocalDate[]{null, com.kalo.common.util.LicenseValidity.today().minusDays(1)}) {
                var a = account(true);
                jdbcTemplate.update("UPDATE vehicles SET " + column + "=? WHERE id=(SELECT vehicle_id FROM driver_vehicle_assignments WHERE driver_id=? AND active=true)", invalid, a.driverId());
                change(a, "ONLINE").andExpect(status().isBadRequest());
                assertThat(state(a)).isEqualTo("OFFLINE");
                change(a, "OFFLINE").andExpect(status().isOk());
            }
        }
    }
    @Test void inactiveVehicleRefusesOnline() throws Exception {
        var a = account(true);
        jdbcTemplate.update("UPDATE vehicles SET status='SUSPENDED' WHERE id=(SELECT vehicle_id FROM driver_vehicle_assignments WHERE driver_id=? AND active=true)", a.driverId());
        change(a, "ONLINE").andExpect(status().isBadRequest());
        assertThat(state(a)).isEqualTo("OFFLINE");
    }
    @Test void busyCannotBeSetOrOverwritten() throws Exception {
        var a = account(true);
        change(a, "BUSY").andExpect(status().isBadRequest());
        jdbcTemplate.update("UPDATE drivers SET availability_status='BUSY' WHERE id=?", a.driverId());
        change(a, "OFFLINE").andExpect(status().isBadRequest());
        assertThat(state(a)).isEqualTo("BUSY");
    }
    @Test void rejectsAnonymousAndOtherRoles() throws Exception {
        String body = "{\"availabilityStatus\":\"OFFLINE\"}";
        mockMvc.perform(put("/api/v1/driver/me/availability").contentType(APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        for (var role : new UserRole[]{UserRole.PARTNER, UserRole.CUSTOMER, UserRole.ADMIN}) {
            var user = fixtures.user(role, UserStatus.ACTIVE);
            mockMvc.perform(put("/api/v1/driver/me/availability").header("Authorization", bearer(tokenFor(user)))
                    .contentType(APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
    }
    @Test void revokedAccountCannotChangeStatus() throws Exception {
        var a = account(true); var token = tokenFor(a.user());
        jdbcTemplate.update("UPDATE drivers SET user_id=NULL WHERE id=?", a.driverId());
        mockMvc.perform(put("/api/v1/driver/me/availability").header("Authorization", bearer(token))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"ONLINE\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(state(a)).isEqualTo("OFFLINE");
    }
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    @Test void busyCommittedWhileRequestWaitsIsNotOverwritten() throws Exception {
        var a = account(true);
        var token = tokenFor(a.user());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = transactions.execute(tx -> {
                jdbcTemplate.queryForObject("SELECT id FROM drivers WHERE id=? FOR UPDATE", Long.class, a.driverId());
                var pending = pool.submit(() -> mockMvc.perform(put("/api/v1/driver/me/availability")
                        .header("Authorization", bearer(token)).contentType(APPLICATION_JSON)
                        .content("{\"availabilityStatus\":\"OFFLINE\"}"))
                        .andReturn().getResponse().getStatus());
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
                    if (pending.isDone()) throw new IllegalStateException("Request finished before lock wait");
                    Long count = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                          AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM drivers WHERE id =%FOR UPDATE%'
                        """, Long.class);
                    if (count != null && count > 0) { waiting = true; break; }
                    try { Thread.sleep(50); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(ex);
                    }
                }
                if (!waiting) throw new IllegalStateException("Availability request did not reach driver lock");
                jdbcTemplate.update("UPDATE drivers SET availability_status='BUSY' WHERE id=?", a.driverId());
                return pending;
            });
            assertThat(result.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(400);
            assertThat(state(a)).isEqualTo("BUSY");
        } finally { pool.shutdownNow(); }
    }

}
