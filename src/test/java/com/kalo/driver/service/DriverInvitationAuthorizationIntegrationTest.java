package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
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

import static org.assertj.core.api.Assertions.*;

/** Exercises the Spring proxy and actual company-scoped PostgreSQL queries. */
class DriverInvitationAuthorizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DriverInvitationAuthorization authorization;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requiresIssuingTransaction() {
        TaxiCompany company = fixtures.approvedCompany();
        authenticate(company);
        assertThatThrownBy(() -> authorization.requireEligibleDriver(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void authorizesOwnDriverUsingAuthenticatedOwner() {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        authenticate(company);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Driver authorized = authorization.requireEligibleDriver(driver.getId());
            assertThat(authorized.getId()).isEqualTo(driver.getId());
            assertThat(authorized.getCompany().getId()).isEqualTo(company.getId());
        });
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM driver_invitations", Long.class))
                .isZero();
    }

    @Test
    void foreignDriverIsIndistinguishableFromMissingDriver() {
        TaxiCompany own = fixtures.approvedCompany();
        TaxiCompany foreign = fixtures.approvedCompany();
        Driver driver = fixtures.driver(foreign, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        authenticate(own);
        for (Long id : new Long[]{driver.getId(), Long.MAX_VALUE}) {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> authorization.requireEligibleDriver(id)))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Driver not found");
        }
    }

    @Test
    void rejectsInactiveOwnedDriver() {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.INACTIVE, DriverAvailabilityStatus.OFFLINE);
        authenticate(company);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> authorization.requireEligibleDriver(driver.getId())))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    void checksCurrentCompanyStatusFromDatabase() {
        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        authenticate(company);
        jdbcTemplate.update("UPDATE taxi_companies SET status = 'SUSPENDED' WHERE id = ?", company.getId());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> authorization.requireEligibleDriver(driver.getId())))
                .isInstanceOf(InvalidOperationException.class);
    }

    private void authenticate(TaxiCompany company) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(company.getOwner().getPhone(), "unused",
                        AuthorityUtils.createAuthorityList("ROLE_PARTNER")));
    }
}
