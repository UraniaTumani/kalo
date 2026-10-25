package com.kalo.driver.service;

import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class DriverInvitationIssuerIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationPersistence persistence;
    @Autowired DriverInvitationRepository invitations;
    @Autowired PlatformTransactionManager transactions;
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void issuanceRecordsAuthenticatedCompanyOwnerId() {
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        var unrelated = fixtures.approvedCompany();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                company.getOwner().getPhone(),"unused",AuthorityUtils.createAuthorityList("ROLE_PARTNER")));
        Long id = new TransactionTemplate(transactions).execute(s -> persistence.stageHash(driver.getId(),hash()));
        Long issuer = invitations.findById(id).orElseThrow().getIssuedByUserId();
        assertThat(issuer).isEqualTo(company.getOwner().getId()).isNotEqualTo(unrelated.getOwner().getId());
    }
    @Test void legacyInvitationWithoutActorRemainsValidAndUnattributed() {
        var invitation = invitation(); invitations.saveAndFlush(invitation);
        var loaded = invitations.findById(invitation.getId()).orElseThrow();
        assertThat(loaded.getIssuedByUserId()).isNull();
        assertThat(loaded.isRedeemable(Instant.now())).isTrue();
    }
    @Test void databaseRejectsNonexistentIssuer() {
        var invitation = invitation(); invitation.recordIssuer(Long.MAX_VALUE);
        assertThatThrownBy(() -> invitations.saveAndFlush(invitation)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void historicalActorIdSurvivesUserProfileChanges() {
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                company.getOwner().getPhone(),"unused",AuthorityUtils.createAuthorityList("ROLE_PARTNER")));
        Long id = new TransactionTemplate(transactions).execute(s -> persistence.stageHash(driver.getId(),hash()));
        jdbcTemplate.update("UPDATE users SET first_name = 'Renamed', phone = '+355690000008' WHERE id = ?",company.getOwner().getId());
        assertThat(invitations.findById(id).orElseThrow().getIssuedByUserId()).isEqualTo(company.getOwner().getId());
    }
    private DriverInvitation invitation() {
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE);
        var invitation = new DriverInvitation(); invitation.setDriver(driver); invitation.setCompany(company);
        invitation.setTokenHash(hash()); invitation.setExpiresAt(Instant.now().plusSeconds(3600)); return invitation;
    }
    private String hash() {
        var codec = new DriverInvitationTokenCodec(); return codec.digest(codec.generate());
    }
}
