package com.kalo.driver.service;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.*;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.user.repository.UserRepository;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties="app.driver-invitation.redemption-enabled=true")
class DriverInvitationRedemptionIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationRedemption redemption;
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    private static final String PASSWORD="TestPass123!";
    private record Invite(Long id,Long driverId,Long companyId,Long deliveryId,String token,String phone) {}
    private Invite invite() {
        var company=fixtures.approvedCompany(); var driver=fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        var codec=new DriverInvitationTokenCodec(); String token=codec.generate();
        var invitation=new DriverInvitation(); invitation.setCompany(company);invitation.setDriver(driver);
        invitation.recordIssuer(company.getOwner().getId());invitation.setTokenHash(codec.digest(token));
        invitation.setExpiresAt(Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)); invitations.saveAndFlush(invitation);
        var cipher=new DriverInvitationDeliveryCipher(new byte[32]);
        String encrypted=cipher.encrypt(token,invitation.getId(),driver.getId(),driver.getPhone(),invitation.getExpiresAt());
        var queue=deliveries.saveAndFlush(new DriverInvitationDelivery(invitation,driver.getPhone(),"test-key",encrypted,Instant.now()));
        return new Invite(invitation.getId(),driver.getId(),company.getId(),queue.getId(),token,driver.getPhone());
    }
    private void rejected(Invite invitation) {
        assertThatThrownBy(()->redemption.redeemToPendingAccount(invitation.token(),PASSWORD))
                .isInstanceOf(InvalidOperationException.class).hasMessage("Invalid driver invitation");
    }
    private Long linkedUser(Invite invitation) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM drivers WHERE id=?",Long.class,invitation.driverId());
    }
    @Test void createsHashedPendingDriverAndConsumesInvitationAtomically() {
        var invitation=invite(); Long id=redemption.redeemToPendingAccount(invitation.token(),PASSWORD);
        var user=users.findById(id).orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.DRIVER);assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(user.isPhoneVerified()).isFalse();assertThat(user.getPhone()).isEqualTo(invitation.phone());
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);assertThat(passwords.matches(PASSWORD,user.getPasswordHash())).isTrue();
        assertThat(linkedUser(invitation)).isEqualTo(id);
        assertThat(invitations.findById(invitation.id()).orElseThrow().getRedeemedAt()).isNotNull();
        assertThat(deliveries.findById(invitation.deliveryId()).orElseThrow().getEncryptedPayload()).isNull();
    }
    @Test void sameTokenCannotCreateSecondAccount() {
        var invitation=invite();redemption.redeemToPendingAccount(invitation.token(),PASSWORD);long count=users.count();
        rejected(invitation);assertThat(users.count()).isEqualTo(count);
    }
    @Test void expiredTokenCannotBeConsumed() {
        var invitation=invite();jdbcTemplate.update("UPDATE driver_invitations SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",invitation.id());
        rejected(invitation);assertThat(linkedUser(invitation)).isNull();
    }
    @Test void revokedOrPreviouslyRedeemedTokenIsRejected() {
        for(String column:new String[]{"revoked_at","redeemed_at"}) {
            var invitation=invite();jdbcTemplate.update("UPDATE driver_invitations SET "+column+"=CURRENT_TIMESTAMP WHERE id=?",invitation.id());
            rejected(invitation);assertThat(linkedUser(invitation)).isNull();
        }
    }
    @Test void existingCustomerPartnerOrDriverAccountIsNeverPromotedOrLinked() {
        for(UserRole role:new UserRole[]{UserRole.CUSTOMER,UserRole.PARTNER,UserRole.DRIVER}) {
            var invitation=invite();var existing=fixtures.user(role,UserStatus.ACTIVE);
            jdbcTemplate.update("UPDATE users SET phone=? WHERE id=?",invitation.phone(),existing.getId());
            rejected(invitation);assertThat(linkedUser(invitation)).isNull();
            assertThat(users.findById(existing.getId()).orElseThrow().getRole()).isEqualTo(role);
            assertThat(invitations.findById(invitation.id()).orElseThrow().getRedeemedAt()).isNull();
        }
    }
    @Test void destinationChangeInvalidatesOldInvitation() {
        var invitation=invite();jdbcTemplate.update("UPDATE drivers SET phone='+355690000009' WHERE id=?",invitation.driverId());
        rejected(invitation);assertThat(linkedUser(invitation)).isNull();
    }
    @Test void inactiveDriverOrSuspendedCompanyCannotRedeem() {
        var inactive=invite();jdbcTemplate.update("UPDATE drivers SET status='INACTIVE' WHERE id=?",inactive.driverId());rejected(inactive);
        var suspended=invite();jdbcTemplate.update("UPDATE taxi_companies SET status='SUSPENDED' WHERE id=?",suspended.companyId());rejected(suspended);
    }
    @Test void missingDeliverySnapshotCannotAuthorizeLinking() {
        var invitation=invite();deliveries.deleteById(invitation.deliveryId());rejected(invitation);
    }
    @Test void historicalUnattributedInvitationCannotCreateAccount() {
        var invitation=invite();jdbcTemplate.update("UPDATE driver_invitations SET issued_by_user_id=NULL WHERE id=?",invitation.id());rejected(invitation);
    }
    @Test void callerFailureRollsBackAccountLinkAndRedemptionThenRetryCanSucceed() {
        var invitation=invite();long before=users.count();
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(s->{
            redemption.redeemToPendingAccount(invitation.token(),PASSWORD);throw new IllegalStateException("Abort");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Abort");
        assertThat(users.count()).isEqualTo(before);assertThat(linkedUser(invitation)).isNull();
        assertThat(invitations.findById(invitation.id()).orElseThrow().getRedeemedAt()).isNull();
        assertThat(deliveries.findById(invitation.deliveryId()).orElseThrow().getEncryptedPayload()).isNotNull();
        assertThat(redemption.redeemToPendingAccount(invitation.token(),PASSWORD)).isNotNull();
    }
    @Test void concurrentRedemptionsCreateExactlyOneAccount() throws Exception {
        var invitation=invite();var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<Boolean> attempt=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("No start");
            try {redemption.redeemToPendingAccount(invitation.token(),PASSWORD);return true;}catch(InvalidOperationException expected){return false;}};
        try {
            var first=executor.submit(attempt);var second=executor.submit(attempt);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            List<Boolean> outcomes=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true,false);
            assertThat(users.findAll().stream().filter(u->u.getRole()==UserRole.DRIVER).count()).isEqualTo(1);
        } finally {start.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void redeemedPendingAccountStillCannotLogIn() throws Exception {
        var invitation=invite();redemption.redeemToPendingAccount(invitation.token(),PASSWORD);
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\""+invitation.phone()+"\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isUnauthorized());
    }
    @Test void existingDriverLinkCannotBeOverwritten() {
        var invitation=invite();var existing=fixtures.customer();
        jdbcTemplate.update("UPDATE drivers SET user_id=? WHERE id=?",existing.getId(),invitation.driverId());
        rejected(invitation);assertThat(linkedUser(invitation)).isEqualTo(existing.getId());
        assertThat(invitations.findById(invitation.id()).orElseThrow().getRedeemedAt()).isNull();
    }
    @Test void changedCompanyOwnershipInvalidatesInvitationScope() {
        var invitation=invite();var other=fixtures.approvedCompany();
        jdbcTemplate.update("UPDATE drivers SET company_id=? WHERE id=?",other.getId(),invitation.driverId());
        rejected(invitation);assertThat(linkedUser(invitation)).isNull();
    }

    @Test void finalActivationIsDisabledInFoundationConfiguration() {
        var invitation=invite();
        assertThatThrownBy(()->redemption.activate(invitation.token(),PASSWORD)).isInstanceOf(IllegalStateException.class);
        assertThat(linkedUser(invitation)).isNull();
    }

}
