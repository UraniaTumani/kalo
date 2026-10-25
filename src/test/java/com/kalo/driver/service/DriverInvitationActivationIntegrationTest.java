package com.kalo.driver.service;
import com.jayway.jsonpath.JsonPath;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.*;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.repository.UserRepository;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties={"app.driver-invitation.activation-api-enabled=true","app.driver-invitation.activation-enabled=true","app.driver-invitation.redemption-enabled=false"})
class DriverInvitationActivationIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationRedemption activation;
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    private static final String PASSWORD="TestPass123!";
    private record Invite(Long id,Long driverId,Long companyId,Long deliveryId,String phone,String token) {}
    private Invite invite(boolean uncertain) {
        var company=fixtures.approvedCompany();var driver=fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        var codec=new DriverInvitationTokenCodec();String token=codec.generate();var invitation=new DriverInvitation();
        invitation.setCompany(company);invitation.setDriver(driver);invitation.recordIssuer(company.getOwner().getId());
        invitation.setTokenHash(codec.digest(token));invitation.setExpiresAt(Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS));
        invitations.saveAndFlush(invitation);Instant now=Instant.now();
        var cipher=new DriverInvitationDeliveryCipher(new byte[32]);
        var queue=new DriverInvitationDelivery(invitation,driver.getPhone(),"test-key",
                cipher.encrypt(token,invitation.getId(),driver.getId(),driver.getPhone(),invitation.getExpiresAt()),now);
        queue.recordProviderMessageId(DriverInvitationMessageId.create(driver.getPhone(),invitation.getTokenHash(),"driver-invitation-"+invitation.getId()));
        if(uncertain)queue.requireReview(now);else queue.recordAccepted(now);
        deliveries.saveAndFlush(queue);
        return new Invite(invitation.getId(),driver.getId(),company.getId(),queue.getId(),driver.getPhone(),token);
    }
    private Invite invite(){return invite(false);}
    private void rejected(Invite invitation) {
        assertThatThrownBy(()->activation.activate(invitation.token(),PASSWORD))
                .isInstanceOf(InvalidOperationException.class).hasMessage("Invalid driver invitation");
    }
    private Long linked(Invite invitation){return jdbcTemplate.queryForObject("SELECT user_id FROM drivers WHERE id=?",Long.class,invitation.driverId());}
    @Test void createsVerifiedActiveDriverWithHashAndAtomicBinding() {
        var invitation=invite();Long id=activation.activate(invitation.token(),PASSWORD);var user=users.findById(id).orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.DRIVER);assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.isPhoneVerified()).isTrue();assertThat(user.getPhone()).isEqualTo(invitation.phone());
        assertThat(passwords.matches(PASSWORD,user.getPasswordHash())).isTrue();assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(linked(invitation)).isEqualTo(id);assertThat(invitations.findById(invitation.id()).orElseThrow().getRedeemedAt()).isNotNull();
    }
    @Test void activatedAccountWorksThroughLoginJwtAndRefresh() throws Exception {
        var invitation=invite();activation.activate(invitation.token(),PASSWORD);
        String body=mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"phone\":\""+invitation.phone()+"\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String access=JsonPath.read(body,"$.accessToken");String refresh=JsonPath.read(body,"$.refreshToken");
        mockMvc.perform(get("/api/v1/me").header("Authorization",bearer(access))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON).content("{\"refreshToken\":\""+refresh+"\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/partner/drivers").header("Authorization",bearer(access))).andExpect(status().isForbidden());
    }
    @Test void consumedActivationTokenCannotBeUsedTwice() {
        var invitation=invite();activation.activate(invitation.token(),PASSWORD);long count=users.count();
        rejected(invitation);assertThat(users.count()).isEqualTo(count);
    }
    @Test void expiredOrRevokedInvitationCannotActivate() {
        var expired=invite();jdbcTemplate.update("UPDATE driver_invitations SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",expired.id());rejected(expired);
        var revoked=invite();jdbcTemplate.update("UPDATE driver_invitations SET revoked_at=CURRENT_TIMESTAMP WHERE id=?",revoked.id());rejected(revoked);
    }
    @Test void changedPhoneCannotVerifyAnotherDestination() {
        var invitation=invite();jdbcTemplate.update("UPDATE drivers SET phone='+355690000009' WHERE id=?",invitation.driverId());
        rejected(invitation);assertThat(linked(invitation)).isNull();
    }
    @Test void existingAccountRolesAreNotPromotedOrReused() {
        for(UserRole role:new UserRole[]{UserRole.CUSTOMER,UserRole.PARTNER,UserRole.DRIVER}) {
            var invitation=invite();var existing=fixtures.user(role,UserStatus.ACTIVE);
            jdbcTemplate.update("UPDATE users SET phone=? WHERE id=?",invitation.phone(),existing.getId());rejected(invitation);
            assertThat(linked(invitation)).isNull();assertThat(users.findById(existing.getId()).orElseThrow().getRole()).isEqualTo(role);
        }
    }
    @Test void inactiveDriverOrCompanyCannotActivate() {
        var inactive=invite();jdbcTemplate.update("UPDATE drivers SET status='INACTIVE' WHERE id=?",inactive.driverId());rejected(inactive);
        var suspended=invite();jdbcTemplate.update("UPDATE taxi_companies SET status='SUSPENDED' WHERE id=?",suspended.companyId());rejected(suspended);
    }
    @Test void unsentCancelledFailedOrMismatchedAttemptCannotActivate() {
        for(String state:new String[]{"PENDING","CANCELLED","FAILED"}) {
            var invitation=invite();
            if(state.equals("PENDING"))jdbcTemplate.update("UPDATE driver_invitation_deliveries SET state='PENDING',encrypted_payload='unused',attempts=0,provider_message_id=NULL WHERE id=?",invitation.deliveryId());
            else jdbcTemplate.update("UPDATE driver_invitation_deliveries SET state=? WHERE id=?",state,invitation.deliveryId());
            rejected(invitation);
        }
        var mismatched=invite();jdbcTemplate.update("UPDATE driver_invitation_deliveries SET provider_message_id='00000000-0000-0000-0000-000000000001' WHERE id=?",mismatched.deliveryId());rejected(mismatched);
    }
    @Test void correctTokenCanActivateAfterUncertainDeliveryAttempt() {
        var invitation=invite(true);Long id=activation.activate(invitation.token(),PASSWORD);
        assertThat(users.findById(id).orElseThrow().isPhoneVerified()).isTrue();
        assertThat(deliveries.findById(invitation.deliveryId()).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
    }
    @Test void rollbackDoesNotLeaveVerifiedAccountOrConsumedToken() {
        var invitation=invite();
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(s->{
            activation.activate(invitation.token(),PASSWORD);throw new IllegalStateException("Abort");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Abort");
        assertThat(users.findByPhone(invitation.phone())).isEmpty();assertThat(linked(invitation)).isNull();
        assertThat(invitations.findById(invitation.id()).orElseThrow().getRedeemedAt()).isNull();
        assertThat(activation.activate(invitation.token(),PASSWORD)).isNotNull();
    }
    @Test void concurrentActivationsCreateOnlyOneAccount() throws Exception {
        var invitation=invite();var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<Boolean> attempt=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("No start");
            try{activation.activate(invitation.token(),PASSWORD);return true;}catch(InvalidOperationException expected){return false;}};
        try {
            var one=executor.submit(attempt);var two=executor.submit(attempt);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            List<Boolean> results=List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));assertThat(results).containsExactlyInAnyOrder(true,false);
            assertThat(users.findAll().stream().filter(u->u.getRole()==UserRole.DRIVER).count()).isEqualTo(1);
        } finally{start.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void activationFlagDoesNotEnablePendingFoundationMethod() {
        var invitation=invite();assertThatThrownBy(()->activation.redeemToPendingAccount(invitation.token(),PASSWORD)).isInstanceOf(IllegalStateException.class);
        assertThat(linked(invitation)).isNull();
    }
    @Test void previouslyCreatedPendingAccountIsNeverSilentlyPromoted() {
        var invitation=invite();var pending=fixtures.user(UserRole.DRIVER,UserStatus.PENDING);
        jdbcTemplate.update("UPDATE users SET phone=?,phone_verified=FALSE WHERE id=?",invitation.phone(),pending.getId());
        jdbcTemplate.update("UPDATE drivers SET user_id=? WHERE id=?",pending.getId(),invitation.driverId());
        jdbcTemplate.update("UPDATE driver_invitations SET redeemed_at=CURRENT_TIMESTAMP WHERE id=?",invitation.id());
        rejected(invitation);var user=users.findById(pending.getId()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);assertThat(user.isPhoneVerified()).isFalse();
    }
    @Test void unknownWellFormedTokenCannotCreateAccount() {
        long before=users.count();String unknown=new DriverInvitationTokenCodec().generate();
        assertThatThrownBy(()->activation.activate(unknown,PASSWORD)).isInstanceOf(InvalidOperationException.class)
                .hasMessage("Invalid driver invitation");assertThat(users.count()).isEqualTo(before);
    }

    private org.springframework.test.web.servlet.ResultActions activateHttp(String token,String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/driver/activate").contentType(APPLICATION_JSON)
                .content("{\"token\":\""+token+"\",\"password\":\""+password+"\"}"));
    }
    @Test void anonymousApiActivationReturnsNoSecretsAndAllowsLogin() throws Exception {
        var invitation=invite();
        activateHttp(invitation.token(),PASSWORD).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(""));
        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"phone\":\""+invitation.phone()+"\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk());
    }
    @Test void apiRejectsReusedInvitation() throws Exception {
        var invitation=invite();activateHttp(invitation.token(),PASSWORD).andExpect(status().isNoContent());
        activateHttp(invitation.token(),PASSWORD).andExpect(status().isBadRequest());
    }
    @Test void apiRejectsUnknownAndMalformedTokensWithoutEchoingSecrets() throws Exception {
        for(String token:new String[]{new DriverInvitationTokenCodec().generate(),"invalid"}) {
            var body=activateHttp(token,PASSWORD).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(token,PASSWORD);
        }
    }
    @Test void apiRejectsInvalidPasswordLengthsIncludingUtf8Overflow() throws Exception {
        var invitation=invite();
        for(String password:new String[]{"short","x".repeat(73),"é".repeat(37)})
            activateHttp(invitation.token(),password).andExpect(status().isBadRequest());
        assertThat(linked(invitation)).isNull();assertThat(invitations.findById(invitation.id()).orElseThrow().getRedeemedAt()).isNull();
    }
    @Test void apiRejectsMissingFieldsAndMalformedJson() throws Exception {
        for(String body:new String[]{"{}","null","{","{\"token\":null,\"password\":null}"})
            mockMvc.perform(post("/api/v1/auth/driver/activate").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
    }
    @Test void apiDoesNotAcceptTokenInQueryString() throws Exception {
        var invitation=invite();
        mockMvc.perform(post("/api/v1/auth/driver/activate").param("token",invitation.token()).param("password",PASSWORD))
                .andExpect(status().isBadRequest());assertThat(linked(invitation)).isNull();
    }
    @Test void apiGetCannotConsumeInvitation() throws Exception {
        var invitation=invite();mockMvc.perform(get("/api/v1/auth/driver/activate")).andExpect(status().isMethodNotAllowed());
        assertThat(linked(invitation)).isNull();
    }

}
