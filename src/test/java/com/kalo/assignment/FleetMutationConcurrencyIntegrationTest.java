package com.kalo.assignment;

import com.jayway.jsonpath.JsonPath;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class FleetMutationConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired TransactionTemplate transactions;
    private String partner(TestDataFactory.BookableCompany taxi) throws Exception { return bearer(tokenFor(taxi.company().getOwner())); }
    private MockHttpServletRequestBuilder accept(TestDataFactory.BookableCompany taxi, long ride) throws Exception {
        return post("/api/v1/partner/rides/" + ride + "/accept").header("Authorization", partner(taxi))
                .contentType(APPLICATION_JSON).content("{\"driverId\":" + taxi.driver().getId() + "}");
    }
    private MockHttpServletRequestBuilder deactivate(TestDataFactory.BookableCompany taxi) throws Exception {
        return delete("/api/v1/partner/vehicles/" + taxi.vehicle().getId()).header("Authorization", partner(taxi));
    }
    private int call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }
    private void race(Long company, MockHttpServletRequestBuilder waiting, Callable<Integer> winner,
                      int winnerStatus, int waitingStatus) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        Future<Integer> pending = null;
        try {
            pending = transactions.execute(tx -> {
                jdbcTemplate.queryForObject("SELECT id FROM taxi_companies WHERE id = ? FOR UPDATE", Long.class, company);
                int holderPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                var future = pool.submit(() -> call(waiting));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
                    if (future.isDone()) throw new IllegalStateException("Request bypassed company lock");
                    Long count = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname=current_database() AND wait_event_type='Lock'
                          AND ? = ANY(pg_blocking_pids(pid))
                          AND query LIKE 'SELECT id FROM taxi_companies WHERE id =%FOR UPDATE%'
                        """, Long.class, holderPid);
                    if (count != null && count > 0) { blocked = true; break; }
                    try { Thread.sleep(30); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                    }
                }
                if (!blocked) throw new IllegalStateException("Request did not reach company lock");
                try { assertThat(winner.call()).isEqualTo(winnerStatus); }
                catch (Exception e) { throw new IllegalStateException(e); }
                return future;
            });
            assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(waitingStatus);
        } finally {
            if (pending != null) pending.cancel(true);
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }
    @Test void deactivationCommittedBeforeAcceptanceIsRechecked() throws Exception {
        var taxi = fixtures.bookableCompany(); long ride = ride();
        race(taxi.company().getId(), accept(taxi, ride), () -> call(deactivate(taxi)), 204, 400);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM rides WHERE id=?", String.class, ride)).isEqualTo("REQUESTED");
        assertThat(jdbcTemplate.queryForObject("SELECT availability_status FROM drivers WHERE id=?", String.class, taxi.driver().getId())).isEqualTo("OFFLINE");
    }
    @Test void acceptanceCommittedBeforeDeactivationPreservesBusyDriverAndVehicle() throws Exception {
        var taxi = fixtures.bookableCompany(); long ride = ride();
        race(taxi.company().getId(), deactivate(taxi), () -> call(accept(taxi, ride)), 200, 400);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM vehicles WHERE id=?", String.class, taxi.vehicle().getId())).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT availability_status FROM drivers WHERE id=?", String.class, taxi.driver().getId())).isEqualTo("BUSY");
    }
    @Test void deactivationCommittedBeforeSelfOnlineIsRechecked() throws Exception {
        var taxi = fixtures.bookableCompany();
        var user = fixtures.user(UserRole.DRIVER, UserStatus.ACTIVE);
        jdbcTemplate.update("UPDATE drivers SET user_id=?, phone=?, availability_status='OFFLINE' WHERE id=?", user.getId(), user.getPhone(), taxi.driver().getId());
        var online = put("/api/v1/driver/me/availability").header("Authorization", bearer(tokenFor(user)))
                .contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"ONLINE\"}");
        race(taxi.company().getId(), online, () -> call(deactivate(taxi)), 204, 400);
        assertThat(jdbcTemplate.queryForObject("SELECT availability_status FROM drivers WHERE id=?", String.class, taxi.driver().getId())).isEqualTo("OFFLINE");
    }
    @Test void onlineCommittedBeforeUnassignmentPreservesAssignment() throws Exception {
        var taxi = fixtures.bookableCompany();
        jdbcTemplate.update("UPDATE drivers SET availability_status='OFFLINE' WHERE id=?", taxi.driver().getId());
        Long assignment = jdbcTemplate.queryForObject("SELECT id FROM driver_vehicle_assignments WHERE driver_id=? AND active=true", Long.class, taxi.driver().getId());
        var unassign = delete("/api/v1/partner/driver-vehicle-assignments/" + assignment).header("Authorization", partner(taxi));
        var online = patch("/api/v1/partner/drivers/" + taxi.driver().getId() + "/availability")
                .header("Authorization", partner(taxi)).contentType(APPLICATION_JSON).content("{\"availabilityStatus\":\"ONLINE\"}");
        race(taxi.company().getId(), unassign, () -> call(online), 200, 400);
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM driver_vehicle_assignments WHERE id=?", Boolean.class, assignment)).isTrue();
    }
    private long ride() throws Exception {
        var auth = bearer(tokenFor(fixtures.customer()));
        var search = mockMvc.perform(post("/api/v1/rides/search").header("Authorization", auth).contentType(APPLICATION_JSON)
                .content("{\"pickupLatitude\":" + TestDataFactory.TIRANA_LAT + ",\"pickupLongitude\":" + TestDataFactory.TIRANA_LNG
                        + ",\"destinationLatitude\":41.32,\"destinationLongitude\":19.83}"))
                .andReturn().getResponse().getContentAsString();
        long request = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();
        long offer = ((Number) JsonPath.read(search, "$.taxiOptions[0].offerId")).longValue();
        var selected = mockMvc.perform(post("/api/v1/rides/requests/" + request + "/select")
                .header("Authorization", auth).contentType(APPLICATION_JSON).content("{\"offerId\":" + offer + "}"))
                .andReturn().getResponse();
        assertThat(selected.getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(selected.getContentAsString(), "$.rideId")).longValue();
    }
}
