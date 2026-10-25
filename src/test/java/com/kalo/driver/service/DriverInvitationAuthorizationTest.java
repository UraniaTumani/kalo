package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.TaxiCompanyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriverInvitationAuthorizationTest {
    private final TaxiCompanyRepository companies = mock(TaxiCompanyRepository.class);
    private final DriverRepository drivers = mock(DriverRepository.class);
    private final DriverInvitationEligibilityPolicy policy = mock(DriverInvitationEligibilityPolicy.class);
    private final DriverInvitationAuthorization authorization =
            new DriverInvitationAuthorization(companies, drivers, policy);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingAuthenticationBeforeDatabaseAccess() {
        assertThatThrownBy(() -> authorization.requireEligibleDriver(20L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(companies, drivers, policy);
    }

    @Test
    void rejectsOtherRolesAndUnauthenticatedPartner() {
        for (String role : new String[]{"ROLE_CUSTOMER", "ROLE_DRIVER", "ROLE_ADMIN", "ROLE_ANONYMOUS"}) {
            authenticate(role);
            assertThatThrownBy(() -> authorization.requireEligibleDriver(20L))
                    .isInstanceOf(AccessDeniedException.class);
        }
        authenticate("ROLE_PARTNER");
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        assertThatThrownBy(() -> authorization.requireEligibleDriver(20L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(companies, drivers, policy);
    }

    @Test
    void missingCompanyCannotLookUpDriver() {
        authenticate("ROLE_PARTNER");
        when(companies.findForUpdateByOwnerPhone("partner-phone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authorization.requireEligibleDriver(20L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(drivers, policy);
    }

    @Test
    void foreignOrMissingDriverIsNotFoundWithinOwnCompany() {
        company();
        when(drivers.findForUpdateByIdAndCompanyId(20L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authorization.requireEligibleDriver(20L))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Driver not found");
        verifyNoInteractions(policy);
    }

    @Test
    void locksOwnCompanyBeforeOwnedDriverThenChecksEligibility() {
        TaxiCompany company = company();
        Driver driver = new Driver();
        when(drivers.findForUpdateByIdAndCompanyId(20L, 10L)).thenReturn(Optional.of(driver));
        assertThat(authorization.requireEligibleDriver(20L)).isSameAs(driver);
        var order = inOrder(companies, drivers, policy);
        order.verify(companies).findForUpdateByOwnerPhone("partner-phone");
        order.verify(drivers).findForUpdateByIdAndCompanyId(20L, 10L);
        order.verify(policy).validate(driver, company);
    }

    @Test
    void eligibilityFailureStopsAuthorization() {
        TaxiCompany company = company();
        Driver driver = new Driver();
        when(drivers.findForUpdateByIdAndCompanyId(20L, 10L)).thenReturn(Optional.of(driver));
        doThrow(new InvalidOperationException("Driver is not eligible for invitation"))
                .when(policy).validate(driver, company);
        assertThatThrownBy(() -> authorization.requireEligibleDriver(20L))
                .isInstanceOf(InvalidOperationException.class);
    }

    private TaxiCompany company() {
        authenticate("ROLE_PARTNER");
        TaxiCompany company = new TaxiCompany();
        company.setId(10L);
        when(companies.findForUpdateByOwnerPhone("partner-phone")).thenReturn(Optional.of(company));
        return company;
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("partner-phone", "unused",
                        AuthorityUtils.createAuthorityList(role)));
    }
}
