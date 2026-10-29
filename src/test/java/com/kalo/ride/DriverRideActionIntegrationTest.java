package com.kalo.ride;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.entity.RideRequest;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.enums.RideRequestStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.repository.RideRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DriverRideActionIntegrationTest extends AbstractIntegrationTest {
    @Autowired RideRepository rides;
    @Autowired RideRequestRepository requests;
    private record Account(User user, Driver driver) {}
    private Account account(TaxiCompany company) {
        var user = fixtures.user(UserRole.DRIVER, UserStatus.ACTIVE);
        var driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.BUSY);
        jdbcTemplate.update("UPDATE drivers SET user_id=?, phone=? WHERE id=?", user.getId(), user.getPhone(), driver.getId());
        return new Account(user, driver);
    }
    private Ride ride(Account account, RideStatus status) {
        var customer = fixtures.user(UserRole.CUSTOMER, UserStatus.ACTIVE);
        var request = new RideRequest();
        request.setCustomer(customer);
        request.setPickupAddress("Pickup test"); request.setPickupLatitude(41.33); request.setPickupLongitude(19.82);
        request.setDestinationAddress("Destination test"); request.setDestinationLatitude(41.34); request.setDestinationLongitude(19.83);
        request.setStatus(RideRequestStatus.SELECTED); request.setExpiresAt(Instant.now().plusSeconds(300));
        requests.save(request);
        var ride = new Ride(); ride.setRideRequest(request); ride.setCustomer(customer);
        ride.setCompany(account.driver().getCompany()); ride.setDriver(account.driver()); ride.setStatus(status);
        ride.setRequestedAt(Instant.now()); ride.setAcceptedAt(Instant.now());
        return rides.save(ride);
    }
    private org.springframework.test.web.servlet.ResultActions act(Account a, Long rideId, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/driver/me/rides/" + rideId + "/" + action)
                .header("Authorization", bearer(tokenFor(a.user()))));
    }
    private String state(Ride ride) {
        return jdbcTemplate.queryForObject("SELECT status FROM rides WHERE id=?", String.class, ride.getId());
    }
    @Test void assignedDriverCanAdvanceInOrderAndTimestampsPersist() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.DRIVER_ASSIGNED);
        String[] actions = {"driver-arriving", "driver-arrived", "start"};
        String[] states = {"DRIVER_ARRIVING", "DRIVER_ARRIVED", "IN_PROGRESS"};
        String[] dates = {"driver_arriving_at", "driver_arrived_at", "started_at"};
        for (int i=0; i<actions.length; i++) {
            act(own, ride.getId(), actions[i]).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.rideId").value(ride.getId().intValue()))
                    .andExpect(jsonPath("$.status").value(states[i]));
            assertThat(state(ride)).isEqualTo(states[i]);
            assertThat(jdbcTemplate.queryForObject("SELECT " + dates[i] + " FROM rides WHERE id=?", java.sql.Timestamp.class, ride.getId())).isNotNull();
        }
    }
    @Test void cannotSkipOrRepeatSteps() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.DRIVER_ASSIGNED);
        act(own, ride.getId(), "start").andExpect(status().isBadRequest());
        act(own, ride.getId(), "driver-arrived").andExpect(status().isBadRequest());
        assertThat(state(ride)).isEqualTo("DRIVER_ASSIGNED");
        act(own, ride.getId(), "driver-arriving").andExpect(status().isOk());
        var first = jdbcTemplate.queryForObject("SELECT driver_arriving_at FROM rides WHERE id=?", java.sql.Timestamp.class, ride.getId());
        act(own, ride.getId(), "driver-arriving").andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT driver_arriving_at FROM rides WHERE id=?", java.sql.Timestamp.class, ride.getId())).isEqualTo(first);
    }
    @Test void anotherDriverInSameCompanyCannotAct() throws Exception {
        var company = fixtures.approvedCompany(); var own = account(company); var other = account(company);
        var ride = ride(other, RideStatus.DRIVER_ASSIGNED);
        for (var action : new String[]{"driver-arriving", "driver-arrived", "start"}) act(own, ride.getId(), action).andExpect(status().isNotFound());
        assertThat(state(ride)).isEqualTo("DRIVER_ASSIGNED");
    }
    @Test void anotherCompanyCannotAct() throws Exception {
        var own = account(fixtures.approvedCompany()); var other = account(fixtures.approvedCompany());
        var ride = ride(other, RideStatus.DRIVER_ASSIGNED);
        act(own, ride.getId(), "driver-arriving").andExpect(status().isNotFound());
        assertThat(state(ride)).isEqualTo("DRIVER_ASSIGNED");
    }
    @Test void terminalRideCannotAdvance() throws Exception {
        var own = account(fixtures.approvedCompany());
        for (var terminal : new RideStatus[]{RideStatus.CANCELLED, RideStatus.COMPLETED, RideStatus.DECLINED, RideStatus.NO_RESPONSE}) {
            var ride = ride(own, terminal);
            for (var action : new String[]{"driver-arriving", "driver-arrived", "start"}) act(own, ride.getId(), action).andExpect(status().isBadRequest());
            assertThat(state(ride)).isEqualTo(terminal.name());
        }
    }
    @Test void anonymousAndOtherRolesAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/driver/me/rides/1/start")).andExpect(status().isUnauthorized());
        for (var role : new UserRole[]{UserRole.PARTNER, UserRole.CUSTOMER, UserRole.ADMIN}) {
            var user = fixtures.user(role, UserStatus.ACTIVE);
            mockMvc.perform(post("/api/v1/driver/me/rides/1/start").header("Authorization", bearer(tokenFor(user))))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void revokedBindingBlocksExistingToken() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.DRIVER_ASSIGNED);
        var token = tokenFor(own.user());
        jdbcTemplate.update("UPDATE drivers SET user_id=NULL WHERE id=?", own.driver().getId());
        mockMvc.perform(post("/api/v1/driver/me/rides/" + ride.getId() + "/driver-arriving")
                .header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        assertThat(state(ride)).isEqualTo("DRIVER_ASSIGNED");
    }
    @Test void concurrentDuplicateRequestsAdvanceOnlyOnce() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.DRIVER_ASSIGNED);
        var token = tokenFor(own.user());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> call = () -> {
            start.await(); return mockMvc.perform(post("/api/v1/driver/me/rides/" + ride.getId() + "/driver-arriving")
                    .header("Authorization", bearer(token))).andReturn().getResponse().getStatus();
        };
        try {
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(state(ride)).isEqualTo("DRIVER_ARRIVING");
        } finally { pool.shutdownNow(); }
    }
}
