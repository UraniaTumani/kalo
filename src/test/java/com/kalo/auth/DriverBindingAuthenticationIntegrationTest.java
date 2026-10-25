package com.kalo.auth;
import com.jayway.jsonpath.JsonPath;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class DriverBindingAuthenticationIntegrationTest extends AbstractIntegrationTest {
    private record Account(User user,Long driverId,Long companyId) {}
    private record Pair(String access,String refresh) {}
    private Account account() {
        User user=fixtures.user(UserRole.DRIVER,UserStatus.ACTIVE);var company=fixtures.approvedCompany();
        var driver=fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        jdbcTemplate.update("UPDATE drivers SET user_id=?,phone=? WHERE id=?",user.getId(),user.getPhone(),driver.getId());
        return new Account(user,driver.getId(),company.getId());
    }
    private String credentials(User user) { return "{\"phone\":\""+user.getPhone()+"\",\"password\":\""+TestDataFactory.PASSWORD+"\"}"; }
    private Pair loginPair(User user) throws Exception {
        String body=mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(credentials(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Pair(JsonPath.read(body,"$.accessToken"),JsonPath.read(body,"$.refreshToken"));
    }
    private void blocked(User user) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(credentials(user))).andExpect(status().isUnauthorized());
    }
    private void blockedSession(Account account,Pair pair) throws Exception {
        blocked(account.user());
        mockMvc.perform(get("/api/v1/me").header("Authorization",bearer(pair.access()))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content("{\"refreshToken\":\""+pair.refresh()+"\"}")).andExpect(status().isUnauthorized());
        Long active=jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL",Long.class,account.user().getId());
        assertThat(active).isZero();
    }
    @Test void eligibleDriverCanLoginUseJwtAndRefresh() throws Exception {
        var account=account();var pair=loginPair(account.user());
        mockMvc.perform(get("/api/v1/me").header("Authorization",bearer(pair.access()))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content("{\"refreshToken\":\""+pair.refresh()+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
    @Test void activeButUnlinkedDriverCannotLogin() throws Exception { blocked(fixtures.user(UserRole.DRIVER,UserStatus.ACTIVE)); }
    @Test void pendingLinkedDriverCannotLogin() throws Exception {
        var account=account();jdbcTemplate.update("UPDATE users SET status='PENDING' WHERE id=?",account.user().getId());blocked(account.user());
    }
    @Test void unverifiedLinkedDriverCannotLogin() throws Exception {
        var account=account();jdbcTemplate.update("UPDATE users SET phone_verified=FALSE WHERE id=?",account.user().getId());blocked(account.user());
    }
    @Test void phoneMismatchBlocksLoginJwtAndRefresh() throws Exception {
        var account=account();var pair=loginPair(account.user());
        jdbcTemplate.update("UPDATE drivers SET phone='+355690000009' WHERE id=?",account.driverId());blockedSession(account,pair);
    }
    @Test void inactiveDriverRevokesRefreshEvenThoughRequestRollsBackWith401() throws Exception {
        var account=account();var pair=loginPair(account.user());var secondSession=loginPair(account.user());
        jdbcTemplate.update("UPDATE drivers SET status='INACTIVE' WHERE id=?",account.driverId());blockedSession(account,pair);
        jdbcTemplate.update("UPDATE drivers SET status='ACTIVE' WHERE id=?",account.driverId());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content("{\"refreshToken\":\""+secondSession.refresh()+"\"}")).andExpect(status().isUnauthorized());
    }
    @Test void suspendedCompanyBlocksExistingSessions() throws Exception {
        var account=account();var pair=loginPair(account.user());
        jdbcTemplate.update("UPDATE taxi_companies SET status='SUSPENDED' WHERE id=?",account.companyId());blockedSession(account,pair);
    }
    @Test void unapprovedCompanyBlocksExistingSessions() throws Exception {
        var account=account();var pair=loginPair(account.user());
        jdbcTemplate.update("UPDATE taxi_companies SET verification_status='PENDING' WHERE id=?",account.companyId());blockedSession(account,pair);
    }
    @Test void removedUserDriverLinkBlocksExistingSessions() throws Exception {
        var account=account();var pair=loginPair(account.user());
        jdbcTemplate.update("UPDATE drivers SET user_id=NULL WHERE id=?",account.driverId());blockedSession(account,pair);
    }
    @Test void driverDoesNotGainPartnerOrAdminPermissions() throws Exception {
        var account=account();var pair=loginPair(account.user());
        mockMvc.perform(get("/api/v1/partner/drivers").header("Authorization",bearer(pair.access()))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization",bearer(pair.access()))).andExpect(status().isForbidden());
    }
    @Test void disabledDriverAccountBlocksAllAuthenticationPaths() throws Exception {
        var account=account();var pair=loginPair(account.user());
        jdbcTemplate.update("UPDATE users SET status='DISABLED' WHERE id=?",account.user().getId());blockedSession(account,pair);
    }
    @Test void removedPhoneVerificationBlocksExistingSessions() throws Exception {
        var account=account();var pair=loginPair(account.user());
        jdbcTemplate.update("UPDATE users SET phone_verified=FALSE WHERE id=?",account.user().getId());blockedSession(account,pair);
    }

}
