package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverInvitationEligibilityPolicyTest {

    private final DriverInvitationEligibilityPolicy policy =
            new DriverInvitationEligibilityPolicy();

    @Test
    void activeDriverAtApprovedActiveCompanyIsEligible() {
        TaxiCompany company = company(10L, CompanyStatus.ACTIVE, VerificationStatus.APPROVED);
        Driver driver = driver(company, DriverStatus.ACTIVE);
        assertThatCode(() -> policy.validate(driver, company)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDriverOwnedByAnotherCompany() {
        TaxiCompany company = company(10L, CompanyStatus.ACTIVE, VerificationStatus.APPROVED);
        Driver foreignDriver = driver(
                company(11L, CompanyStatus.ACTIVE, VerificationStatus.APPROVED),
                DriverStatus.ACTIVE);
        assertRejected(foreignDriver, company);
    }

    @Test
    void rejectsPendingAndSuspendedCompanies() {
        TaxiCompany approved = company(10L, CompanyStatus.ACTIVE, VerificationStatus.APPROVED);
        TaxiCompany pending = company(10L, CompanyStatus.ACTIVE, VerificationStatus.PENDING);
        TaxiCompany suspended = company(10L, CompanyStatus.SUSPENDED, VerificationStatus.APPROVED);
        assertRejected(driver(approved, DriverStatus.ACTIVE), pending);
        assertRejected(driver(approved, DriverStatus.ACTIVE), suspended);
    }

    @Test
    void rejectsInactiveOrAlreadyLinkedDrivers() {
        TaxiCompany company = company(10L, CompanyStatus.ACTIVE, VerificationStatus.APPROVED);
        assertRejected(driver(company, DriverStatus.INACTIVE), company);
        Driver linked = driver(company, DriverStatus.ACTIVE);
        linked.setUser(new User());
        assertRejected(linked, company);
    }

    @Test
    void rejectsMissingOrUnidentifiedActors() {
        TaxiCompany company = company(10L, CompanyStatus.ACTIVE, VerificationStatus.APPROVED);
        assertRejected(null, company);
        assertRejected(driver(company, DriverStatus.ACTIVE), null);
        TaxiCompany unidentified = company(null, CompanyStatus.ACTIVE, VerificationStatus.APPROVED);
        assertRejected(driver(company, DriverStatus.ACTIVE), unidentified);
    }

    private void assertRejected(Driver driver, TaxiCompany company) {
        assertThatThrownBy(() -> policy.validate(driver, company))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessage("Driver is not eligible for invitation");
    }

    private static TaxiCompany company(
            Long id, CompanyStatus status, VerificationStatus verification) {
        TaxiCompany company = new TaxiCompany();
        company.setId(id);
        company.setStatus(status);
        company.setVerificationStatus(verification);
        return company;
    }

    private static Driver driver(TaxiCompany company, DriverStatus status) {
        Driver driver = new Driver();
        driver.setCompany(company);
        driver.setStatus(status);
        return driver;
    }
}
