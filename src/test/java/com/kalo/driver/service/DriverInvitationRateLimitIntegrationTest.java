package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class DriverInvitationRateLimitIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationRateLimit limits;
    @Autowired DriverInvitationRepository invitations;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void countIsCompanyScopedInclusiveAndIncludesRevokedAndRedeemedRows() {
        Instant boundary = Instant.parse("2026-10-24T17:00:00Z");
        TaxiCompany own = fixtures.approvedCompany();
        Driver driver = driver(own);
        DriverInvitation revoked = invitation(driver, boundary);
        jdbcTemplate.update("UPDATE driver_invitations SET revoked_at = ? WHERE id = ?",
                Timestamp.from(boundary), revoked.getId());
        DriverInvitation redeemed = invitation(driver, boundary.plusSeconds(1));
        jdbcTemplate.update("UPDATE driver_invitations SET redeemed_at = ? WHERE id = ?",
                Timestamp.from(boundary.plusSeconds(2)), redeemed.getId());
        invitation(driver, boundary.minusSeconds(1));
        invitation(driver(fixtures.approvedCompany()), boundary);
        assertThat(invitations.countByCompanyIdAndCreatedAtGreaterThanEqual(own.getId(), boundary))
                .isEqualTo(2);
    }

    @Test
    void recentInvitationPreventsResendAfterAuthorization() {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = driver(company);
        invitation(driver, Instant.now().plusSeconds(10));
        authenticate(company);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> limits.requireAllowedDriver(driver.getId())))
                .isInstanceOf(InvalidOperationException.class).hasMessage("Driver invitation cooldown is active");
        assertThat(invitations.count()).isEqualTo(1);
    }

    @Test
    void rateLimitCannotBeUsedOutsideIssuanceTransaction() {
        assertThatThrownBy(() -> limits.requireAllowedDriver(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Driver driver(TaxiCompany company) {
        return fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
    }

    private DriverInvitation invitation(Driver driver, Instant createdAt) {
        DriverInvitation invitation = new DriverInvitation();
        invitation.setDriver(driver);
        invitation.setCompany(driver.getCompany());
        invitation.setTokenHash(new DriverInvitationTokenCodec().digest(new DriverInvitationTokenCodec().generate()));
        invitation.setExpiresAt(createdAt.plusSeconds(86400));
        DriverInvitation saved = invitations.saveAndFlush(invitation);
        jdbcTemplate.update("UPDATE driver_invitations SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private void authenticate(TaxiCompany company) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(company.getOwner().getPhone(), "unused",
                        AuthorityUtils.createAuthorityList("ROLE_PARTNER")));
    }
}
