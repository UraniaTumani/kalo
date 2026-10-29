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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DriverCurrentRideIntegrationTest extends AbstractIntegrationTest {
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
    private org.springframework.test.web.servlet.ResultActions current(Account a) throws Exception {
        return mockMvc.perform(get("/api/v1/driver/me/ride").header("Authorization", bearer(tokenFor(a.user()))));
    }
    @Test void returnsOwnRideAndOnlyWorkDetails() throws Exception {
        var a = account(fixtures.approvedCompany()); var ride = ride(a, RideStatus.DRIVER_ASSIGNED);
        current(a).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.rideId").value(ride.getId().intValue()))
                .andExpect(jsonPath("$.status").value("DRIVER_ASSIGNED"))
                .andExpect(jsonPath("$.pickupAddress").value("Pickup test"))
                .andExpect(jsonPath("$.pickupLatitude").value(41.33))
                .andExpect(jsonPath("$.destinationLongitude").value(19.83))
                .andExpect(jsonPath("$.customer").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.finalAmount").doesNotExist());
    }
    @Test void noRideReturnsNoContent() throws Exception {
        current(account(fixtures.approvedCompany())).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
    }
    @Test void otherDriversRideInSameCompanyIsHiddenEvenWithQueryIds() throws Exception {
        var company = fixtures.approvedCompany(); var own = account(company); var other = account(company);
        var foreign = ride(other, RideStatus.DRIVER_ASSIGNED);
        mockMvc.perform(get("/api/v1/driver/me/ride").param("driverId", other.driver().getId().toString())
                .param("rideId", foreign.getId().toString()).header("Authorization", bearer(tokenFor(own.user()))))
                .andExpect(status().isNoContent());
    }
    @Test void otherCompanyRideIsHidden() throws Exception {
        var own = account(fixtures.approvedCompany()); var other = account(fixtures.approvedCompany());
        ride(other, RideStatus.IN_PROGRESS);
        current(own).andExpect(status().isNoContent());
    }
    @Test void terminalRidesAreHidden() throws Exception {
        var own = account(fixtures.approvedCompany());
        for (var state : new RideStatus[]{RideStatus.COMPLETED, RideStatus.CANCELLED, RideStatus.DECLINED, RideStatus.NO_RESPONSE}) {
            ride(own, state);
        }
        current(own).andExpect(status().isNoContent());
    }
    @Test void allAssignedActiveStagesAreVisible() throws Exception {
        var own = account(fixtures.approvedCompany()); var ride = ride(own, RideStatus.DRIVER_ASSIGNED);
        for (var state : new RideStatus[]{RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVING, RideStatus.DRIVER_ARRIVED, RideStatus.IN_PROGRESS}) {
            jdbcTemplate.update("UPDATE rides SET status=? WHERE id=?", state.name(), ride.getId());
            current(own).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state.name()));
        }
    }
    @Test void anonymousAndOtherRolesAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/driver/me/ride")).andExpect(status().isUnauthorized());
        for (var role : new UserRole[]{UserRole.PARTNER, UserRole.CUSTOMER, UserRole.ADMIN}) {
            var user = fixtures.user(role, UserStatus.ACTIVE);
            mockMvc.perform(get("/api/v1/driver/me/ride").header("Authorization", bearer(tokenFor(user))))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void revokedBindingBlocksExistingToken() throws Exception {
        var own = account(fixtures.approvedCompany()); ride(own, RideStatus.DRIVER_ASSIGNED);
        var token = tokenFor(own.user());
        jdbcTemplate.update("UPDATE drivers SET user_id=NULL WHERE id=?", own.driver().getId());
        mockMvc.perform(get("/api/v1/driver/me/ride").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }
}
