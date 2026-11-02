package com.kalo.admin;

import com.kalo.support.AbstractIntegrationTest;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FleetDocumentOwnerIntegrationTest extends AbstractIntegrationTest {
    private String url(Long company, String type) {
        return "/api/v1/admin/partners/" + company + "/fleet/owners?ownerType=" + type;
    }
    private String admin() throws Exception { return bearer(tokenFor(fixtures.user(UserRole.ADMIN, UserStatus.ACTIVE))); }
    @Test void listsOnlyOwnersOfSelectedCompanyAndType() throws Exception {
        var own = fixtures.approvedCompany(); var foreign = fixtures.approvedCompany();
        var driver = fixtures.driver(own, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        var vehicle = fixtures.vehicle(own, VehicleStatus.INACTIVE);
        fixtures.driver(foreign, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        fixtures.vehicle(foreign, VehicleStatus.ACTIVE);
        String auth = admin();
        mockMvc.perform(get(url(own.getId(), "DRIVER")).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(driver.getId()))
                .andExpect(jsonPath("$.hasMore").value(false));
        mockMvc.perform(get(url(own.getId(), "VEHICLE")).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(vehicle.getId()));
    }
    @Test void paginationDoesNotTruncateTheFleetAndRejectsInvalidInput() throws Exception {
        var company = fixtures.approvedCompany(); Long last = null;
        for (int n=0;n<21;n++) last = fixtures.vehicle(company, VehicleStatus.ACTIVE).getId();
        String auth = admin(); String base = url(company.getId(), "VEHICLE");
        mockMvc.perform(get(base).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20)).andExpect(jsonPath("$.hasMore").value(true));
        mockMvc.perform(get(base + "&page=1").header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(last))
                .andExpect(jsonPath("$.hasMore").value(false));
        mockMvc.perform(get(base + "&page=-1").header("Authorization", auth)).andExpect(status().isBadRequest());
        mockMvc.perform(get(url(company.getId(), "COMPANY")).header("Authorization", auth)).andExpect(status().isBadRequest());
        mockMvc.perform(get(url(999999L, "DRIVER")).header("Authorization", auth)).andExpect(status().isNotFound());
    }
    @Test void ownerDirectoryIsNotAvailableToAnonymousOrOtherRoles() throws Exception {
        var company = fixtures.approvedCompany(); String base=url(company.getId(), "DRIVER");
        mockMvc.perform(get(base)).andExpect(status().isUnauthorized());
        for (var role : new UserRole[]{UserRole.CUSTOMER,UserRole.PARTNER}) {
            String auth = bearer(tokenFor(fixtures.user(role, UserStatus.ACTIVE)));
            mockMvc.perform(get(base).header("Authorization", auth)).andExpect(status().isForbidden());
        }
        var user=fixtures.user(UserRole.DRIVER, UserStatus.ACTIVE);
        var driver=fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        jdbcTemplate.update("UPDATE drivers SET user_id=?, phone=? WHERE id=?", user.getId(), user.getPhone(), driver.getId());
        mockMvc.perform(get(base).header("Authorization",bearer(tokenFor(user)))).andExpect(status().isForbidden());
    }
}
