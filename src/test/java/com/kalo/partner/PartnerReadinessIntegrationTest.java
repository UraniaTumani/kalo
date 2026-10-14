package com.kalo.partner;

import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.PaymentMethod;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Partner readiness checklist")
class PartnerReadinessIntegrationTest extends AbstractIntegrationTest {

    @Test
    void bookableCompanyIsReady() throws Exception {
        TestDataFactory.BookableCompany fleet = fixtures.bookableCompany();
        check(fleet.company())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyNow").value(true))
                .andExpect(jsonPath("$.blockers.length()").value(0))
                .andExpect(jsonPath("$.checkedAt").exists());
    }

    @Test
    void onboardingCompanyGetsActionableStatusWithoutFleetNoise() throws Exception {
        TaxiCompany company = fixtures.company(
                VerificationStatus.DRAFT, CompanyStatus.INACTIVE, true, Set.of());
        check(company)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyNow").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("NOT_APPROVED"))
                .andExpect(jsonPath("$.blockers[1]").value("NOT_ACTIVE"))
                .andExpect(jsonPath("$.blockers.length()").value(2));
    }

    @Test
    void missingPaymentAndDisabledBookingsAreVisible() throws Exception {
        TaxiCompany company = fixtures.company(
                VerificationStatus.APPROVED, CompanyStatus.ACTIVE, false, Set.of());
        check(company)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyNow").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("BOOKINGS_DISABLED"))
                .andExpect(jsonPath("$.blockers[1]").value("PAYMENT_METHOD_MISSING"));
    }

    @Test
    void companyLicenceExpiryBlocksReadiness() throws Exception {
        TestDataFactory.BookableCompany fleet = fixtures.bookableCompany();
        jdbcTemplate.update(
                "UPDATE taxi_companies SET license_expiry_date = ? WHERE id = ?",
                LocalDate.now().minusDays(1), fleet.company().getId());
        check(fleet.company())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyNow").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("COMPANY_LICENCE_EXPIRED"));
    }

    @Test
    void onlineDriverWithoutRecentGpsIsNotReady() throws Exception {
        TaxiCompany company = fixtures.approvedCompany();
        var driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        var vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);
        fixtures.assign(driver, vehicle);
        fixtures.staleLocation(driver);

        check(company)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyNow").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("NO_ELIGIBLE_TAXI"));
    }

    @Test
    void customerCannotReadCompanyReadiness() throws Exception {
        mockMvc.perform(get("/api/v1/partner/readiness")
                        .header("Authorization", bearer(tokenFor(fixtures.customer()))))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions check(TaxiCompany company)
            throws Exception {
        return mockMvc.perform(get("/api/v1/partner/readiness")
                .header("Authorization", bearer(tokenFor(company.getOwner()))));
    }
}
