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

class DriverRideCompletionIntegrationTest extends AbstractIntegrationTest {
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
    private org.springframework.test.web.servlet.ResultActions complete(Account a, Ride ride, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/driver/me/rides/" + ride.getId() + "/complete")
                .header("Authorization", bearer(tokenFor(a.user())))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"finalAmount\":" + amount + "}"));
    }
    private java.math.BigDecimal amount(Ride ride) {
        return jdbcTemplate.queryForObject("SELECT final_amount FROM rides WHERE id=?", java.math.BigDecimal.class, ride.getId());
    }
    @Test void completionPersistsAmountTimeAndAvailabilityAtomically() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.IN_PROGRESS);
        complete(own, ride, "800.50").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(amount(ride)).isEqualByComparingTo("800.50");
        assertThat(jdbcTemplate.queryForObject("SELECT completed_at FROM rides WHERE id=?", java.sql.Timestamp.class, ride.getId())).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT availability_status FROM drivers WHERE id=?", String.class, own.driver().getId())).isEqualTo("ONLINE");
    }
    @Test void invalidAmountsDoNotChangeRide() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.IN_PROGRESS);
        for (var invalid : new String[]{"null", "0", "-1", "1.234", "10000000000"})
            complete(own, ride, invalid).andExpect(status().isBadRequest());
        assertThat(amount(ride)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM rides WHERE id=?", String.class, ride.getId())).isEqualTo("IN_PROGRESS");
    }
    @Test void duplicateCannotOverwriteAmountOrCompletionTime() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.IN_PROGRESS);
        complete(own, ride, "800").andExpect(status().isOk());
        var time = jdbcTemplate.queryForObject("SELECT completed_at FROM rides WHERE id=?", java.sql.Timestamp.class, ride.getId());
        complete(own, ride, "900").andExpect(status().isBadRequest());
        assertThat(amount(ride)).isEqualByComparingTo("800");
        assertThat(jdbcTemplate.queryForObject("SELECT completed_at FROM rides WHERE id=?", java.sql.Timestamp.class, ride.getId())).isEqualTo(time);
    }
    @Test void wrongStatesAreRejected() throws Exception {
        var own = account(fixtures.approvedCompany());
        for (var state : new RideStatus[]{RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVING, RideStatus.DRIVER_ARRIVED, RideStatus.CANCELLED}) {
            var ride = ride(own, state);
            complete(own, ride, "800").andExpect(status().isBadRequest());
            assertThat(amount(ride)).isNull();
            jdbcTemplate.update("UPDATE rides SET status='CANCELLED' WHERE id=?", ride.getId());
        }
    }
    @Test void foreignDriverAndCompanyCannotComplete() throws Exception {
        var company = fixtures.approvedCompany(); var own = account(company); var other = account(company);
        var ride = ride(other, RideStatus.IN_PROGRESS);
        complete(own, ride, "800").andExpect(status().isNotFound());
        var foreign = account(fixtures.approvedCompany());
        complete(foreign, ride, "800").andExpect(status().isNotFound());
        assertThat(amount(ride)).isNull();
    }
    @Test void revokedBindingCannotComplete() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.IN_PROGRESS); var token = tokenFor(own.user());
        jdbcTemplate.update("UPDATE drivers SET user_id=NULL WHERE id=?", own.driver().getId());
        mockMvc.perform(post("/api/v1/driver/me/rides/" + ride.getId() + "/complete").header("Authorization", bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"finalAmount\":800}"))
                .andExpect(status().isUnauthorized());
        assertThat(amount(ride)).isNull();
    }
    @Test void anonymousAndOtherRolesAreRejected() throws Exception {
        var path = "/api/v1/driver/me/rides/1/complete";
        mockMvc.perform(post(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"finalAmount\":800}"))
                .andExpect(status().isUnauthorized());
        for (var role : new UserRole[]{UserRole.CUSTOMER, UserRole.PARTNER, UserRole.ADMIN}) {
            var user = fixtures.user(role, UserStatus.ACTIVE);
            mockMvc.perform(post(path).header("Authorization", bearer(tokenFor(user)))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"finalAmount\":800}"))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void concurrentCompletionHasOneWinnerAndOneStoredAmount() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.IN_PROGRESS); var token = tokenFor(own.user());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> call = () -> { start.await(); return mockMvc.perform(
                    post("/api/v1/driver/me/rides/" + ride.getId() + "/complete").header("Authorization", bearer(token))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"finalAmount\":800.50}"))
                    .andReturn().getResponse().getStatus(); };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(amount(ride)).isEqualByComparingTo("800.50");
        } finally { pool.shutdownNow(); }
    }
}
