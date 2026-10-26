package com.kalo.driver.controller;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DriverSelfApiIntegrationTest extends AbstractIntegrationTest {
    private record Account(User user, Long driverId, Long companyId) {}

    private Account account() {
        var user = fixtures.user(UserRole.DRIVER, UserStatus.ACTIVE);
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        jdbcTemplate.update("UPDATE drivers SET user_id=?,phone=? WHERE id=?",
                user.getId(), user.getPhone(), driver.getId());
        return new Account(user, driver.getId(), company.getId());
    }

    @Test void returnsOnlyTheAuthenticatedDriverEvenWithForeignIdsInQuery() throws Exception {
        var own = account();
        var other = account();
        mockMvc.perform(get("/api/v1/driver/me")
                .param("driverId", other.driverId().toString())
                .param("companyId", other.companyId().toString())
                .header("Authorization", bearer(tokenFor(own.user()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.driverId").value(own.driverId().intValue()))
                .andExpect(jsonPath("$.companyId").value(own.companyId().intValue()))
                .andExpect(jsonPath("$.phone").value(own.user().getPhone()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.availabilityStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.licenseNumber").doesNotExist());
    }

    @Test void anonymousCannotReadDriverIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/driver/me")).andExpect(status().isUnauthorized());
    }

    @Test void partnerCustomerAndAdminCannotUseDriverRoutes() throws Exception {
        for (var role : new UserRole[]{UserRole.PARTNER, UserRole.CUSTOMER, UserRole.ADMIN}) {
            var user = fixtures.user(role, UserStatus.ACTIVE);
            mockMvc.perform(get("/api/v1/driver/me").header("Authorization", bearer(tokenFor(user))))
                    .andExpect(status().isForbidden());
        }
    }

    private void revokedAfterLogin(String sql) throws Exception {
        var own = account();
        var token = tokenFor(own.user());
        jdbcTemplate.update(sql, own.driverId());
        mockMvc.perform(get("/api/v1/driver/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test void removedBindingBlocksAnExistingToken() throws Exception {
        revokedAfterLogin("UPDATE drivers SET user_id=NULL WHERE id=?");
    }

    @Test void changedDriverPhoneBlocksAnExistingToken() throws Exception {
        revokedAfterLogin("UPDATE drivers SET phone='+355699999998' WHERE id=?");
    }

    @Test void inactiveDriverBlocksAnExistingToken() throws Exception {
        revokedAfterLogin("UPDATE drivers SET status='INACTIVE' WHERE id=?");
    }

    @Test void suspendedCompanyBlocksAnExistingToken() throws Exception {
        var own = account();
        var token = tokenFor(own.user());
        jdbcTemplate.update("UPDATE taxi_companies SET status='SUSPENDED' WHERE id=?", own.companyId());
        mockMvc.perform(get("/api/v1/driver/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test void unapprovedCompanyBlocksAnExistingToken() throws Exception {
        var own = account();
        var token = tokenFor(own.user());
        jdbcTemplate.update("UPDATE taxi_companies SET verification_status='PENDING' WHERE id=?", own.companyId());
        mockMvc.perform(get("/api/v1/driver/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test void callerCannotSelectAnotherDriverByPath() throws Exception {
        var own = account();
        var other = account();
        mockMvc.perform(get("/api/v1/driver/" + other.driverId() + "/me")
                .header("Authorization", bearer(tokenFor(own.user()))))
                .andExpect(status().isNotFound());
    }
}
