package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import org.springframework.stereotype.Component;

/**
 * Eligibility checks for a future partner-authorized driver invitation.
 *
 * This policy is not an authorization boundary by itself: the issuing service
 * must resolve the partner's company from the authenticated principal and load
 * the driver through a company-scoped, locked repository query. No invitation
 * delivery or public activation endpoint exists yet.
 */
@Component
public class DriverInvitationEligibilityPolicy {

    public void validate(Driver driver, TaxiCompany authorizedCompany) {
        if (driver == null || authorizedCompany == null
                || driver.getCompany() == null
                || authorizedCompany.getId() == null
                || !authorizedCompany.getId().equals(driver.getCompany().getId())) {
            throw new InvalidOperationException("Driver is not eligible for invitation");
        }

        if (authorizedCompany.getStatus() != CompanyStatus.ACTIVE
                || authorizedCompany.getVerificationStatus() != VerificationStatus.APPROVED
                || driver.getStatus() != DriverStatus.ACTIVE
                || driver.getUser() != null) {
            throw new InvalidOperationException("Driver is not eligible for invitation");
        }
    }
}
