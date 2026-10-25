package com.kalo.driver.controller;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.driver.service.DriverInvitationDeliveryCipher;
import com.kalo.driver.service.DriverInvitationTokenCodec;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(PartnerDriverInvitationApiIntegrationTest.Encryption.class)
@TestPropertySource(properties={"app.driver-invitation.issuance-api-enabled=true",
        "app.driver-invitation.delivery-key-id=api-test-key","app.driver-invitation.company-limit=2"})
class PartnerDriverInvitationApiIntegrationTest extends AbstractIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class Encryption {
        @Bean DriverInvitationDeliveryCipher invitationCipher() {
            return new DriverInvitationDeliveryCipher(new byte[32]);
        }
    }
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired DriverInvitationDeliveryCipher cipher;

    private Driver driver(TaxiCompany company) {
        return fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
    }
    private String path(Long id) { return "/api/v1/partner/drivers/"+id+"/invitations"; }
    private org.springframework.test.web.servlet.ResultActions invite(Driver driver,String token) throws Exception {
        return mockMvc.perform(post(path(driver.getId())).header("Authorization",bearer(token)));
    }
    private void empty() { assertThat(invitations.count()).isZero();assertThat(deliveries.count()).isZero(); }

    @Test void ownerQueuesEncryptedInvitationWithoutReturningSecrets() throws Exception {
        var company=fixtures.approvedCompany();var driver=driver(company);
        invite(driver,tokenFor(company.getOwner())).andExpect(status().isAccepted())
                .andExpect(content().string("")).andExpect(header().string("Cache-Control","no-store"));
        var invitation=invitations.findAll().get(0);var queue=deliveries.findAll().get(0);
        assertThat(invitations.count()).isEqualTo(1);assertThat(deliveries.count()).isEqualTo(1);
        assertThat(invitation.getIssuedByUserId()).isEqualTo(company.getOwner().getId());
        assertThat(queue.getState()).isEqualTo(DriverInvitationDelivery.State.PENDING);
        assertThat(queue.getAttempts()).isZero();assertThat(queue.getDestination()).isEqualTo(driver.getPhone());
        String raw=cipher.decrypt(queue.getEncryptedPayload(),invitation.getId(),driver.getId(),driver.getPhone(),invitation.getExpiresAt());
        assertThat(new DriverInvitationTokenCodec().digest(raw)).isEqualTo(invitation.getTokenHash());
    }
    @Test void anonymousCannotIssue() throws Exception {
        var driver=driver(fixtures.approvedCompany());
        mockMvc.perform(post(path(driver.getId()))).andExpect(status().isUnauthorized());empty();
    }
    @Test void customerAndAdminCannotIssue() throws Exception {
        var driver=driver(fixtures.approvedCompany());
        for(UserRole role:new UserRole[]{UserRole.CUSTOMER,UserRole.ADMIN})
            invite(driver,tokenFor(fixtures.user(role,UserStatus.ACTIVE))).andExpect(status().isForbidden());
        empty();
    }
    @Test void otherCompanyDriverLooksMissing() throws Exception {
        var owner=fixtures.approvedCompany();var foreign=driver(fixtures.approvedCompany());
        invite(foreign,tokenFor(owner.getOwner())).andExpect(status().isNotFound());empty();
    }
    @Test void missingDriverLooksMissing() throws Exception {
        var company=fixtures.approvedCompany();
        mockMvc.perform(post(path(Long.MAX_VALUE)).header("Authorization",bearer(tokenFor(company.getOwner()))))
                .andExpect(status().isNotFound());empty();
    }
    @Test void inactiveDriverCannotBeInvited() throws Exception {
        var company=fixtures.approvedCompany();var driver=driver(company);
        jdbcTemplate.update("UPDATE drivers SET status='INACTIVE' WHERE id=?",driver.getId());
        invite(driver,tokenFor(company.getOwner())).andExpect(status().isBadRequest());empty();
    }
    @Test void suspendedCompanyCannotIssue() throws Exception {
        var company=fixtures.approvedCompany();var driver=driver(company);String token=tokenFor(company.getOwner());
        jdbcTemplate.update("UPDATE taxi_companies SET status='SUSPENDED' WHERE id=?",company.getId());
        invite(driver,token).andExpect(status().isBadRequest());empty();
    }
    @Test void alreadyLinkedDriverCannotBeInvited() throws Exception {
        var company=fixtures.approvedCompany();var driver=driver(company);var user=fixtures.user(UserRole.DRIVER,UserStatus.PENDING);
        jdbcTemplate.update("UPDATE drivers SET user_id=? WHERE id=?",user.getId(),driver.getId());
        invite(driver,tokenFor(company.getOwner())).andExpect(status().isBadRequest());empty();
    }
    @Test void cooldownDoesNotCreateAnotherInvitation() throws Exception {
        var company=fixtures.approvedCompany();var driver=driver(company);String token=tokenFor(company.getOwner());
        invite(driver,token).andExpect(status().isAccepted());invite(driver,token).andExpect(status().isBadRequest());
        assertThat(invitations.count()).isEqualTo(1);assertThat(deliveries.count()).isEqualTo(1);
    }
    @Test void companyQuotaCoversDifferentDrivers() throws Exception {
        var company=fixtures.approvedCompany();String token=tokenFor(company.getOwner());
        invite(driver(company),token).andExpect(status().isAccepted());
        invite(driver(company),token).andExpect(status().isAccepted());
        invite(driver(company),token).andExpect(status().isBadRequest());
        assertThat(invitations.count()).isEqualTo(2);assertThat(deliveries.count()).isEqualTo(2);
    }
    @Test void invalidPathIdCannotWrite() throws Exception {
        var company=fixtures.approvedCompany();
        mockMvc.perform(post("/api/v1/partner/drivers/not-a-number/invitations")
                .header("Authorization",bearer(tokenFor(company.getOwner())))).andExpect(status().isBadRequest());empty();
    }
}
