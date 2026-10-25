package com.kalo.driver.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal authorization step for invitation issuance. The caller must own the
 * write transaction so company/driver locks survive until issuance commits.
 * Always lock company before driver; never accept company identity from input.
 * This component does not issue, deliver or activate invitations.
 */
@Component
@RequiredArgsConstructor
public class DriverInvitationAuthorization {

    private final TaxiCompanyRepository companyRepository;
    private final DriverRepository driverRepository;
    private final DriverInvitationEligibilityPolicy eligibilityPolicy;

    @Transactional(propagation = Propagation.MANDATORY)
    public Driver requireEligibleDriver(Long driverId) {
        Authentication actor = SecurityContextHolder.getContext().getAuthentication();
        if (actor == null || !actor.isAuthenticated()
                || actor.getAuthorities().stream()
                    .noneMatch(authority -> "ROLE_PARTNER".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("Partner authentication required");
        }
        TaxiCompany company = companyRepository.findForUpdateByOwnerPhone(actor.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Taxi company not found for current partner"));
        Driver driver = driverRepository.findForUpdateByIdAndCompanyId(driverId, company.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
        eligibilityPolicy.validate(driver, company);
        return driver;
    }
}
