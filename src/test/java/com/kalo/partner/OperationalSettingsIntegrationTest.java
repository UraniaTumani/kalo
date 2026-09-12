package com.kalo.partner;

import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.PaymentMethod;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the defect behind the "company invisible in search" report: the GET
 * handed a LAZY @ElementCollection to the serialiser after its transaction had
 * closed and answered 500, which the UI read as "not approved yet" — so the
 * partner could never set a payment method, and a company without one is
 * excluded from search.
 */
@DisplayName("Company operational settings")
class OperationalSettingsIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("reading settings serialises the lazy payment methods")
    void getSettingsLoadsPaymentMethods() throws Exception {

        TaxiCompany company = fixtures.company(
                VerificationStatus.APPROVED,
                CompanyStatus.ACTIVE,
                true,
                Set.of(PaymentMethod.CASH, PaymentMethod.CARD_IN_CAR)
        );

        mockMvc.perform(
                        get("/api/v1/partner/operational-settings")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingEnabled").value(true))
                .andExpect(jsonPath("$.paymentMethods.length()").value(2));
    }

    @Test
    @DisplayName("reading settings works when no payment method is set yet")
    void getSettingsWithEmptyPaymentMethods() throws Exception {

        TaxiCompany company = fixtures.company(
                VerificationStatus.APPROVED, CompanyStatus.ACTIVE, true, Set.of());

        mockMvc.perform(
                        get("/api/v1/partner/operational-settings")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethods.length()").value(0));
    }

    @Test
    @DisplayName("updating settings persists and reads back")
    void updateSettings() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        String token = tokenFor(company.getOwner());

        mockMvc.perform(
                        put("/api/v1/partner/operational-settings")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"bookingEnabled":false,"paymentMethods":["CARD_IN_CAR"]}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingEnabled").value(false));

        mockMvc.perform(
                        get("/api/v1/partner/operational-settings")
                                .header("Authorization", bearer(token))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingEnabled").value(false))
                .andExpect(jsonPath("$.paymentMethods[0]").value("CARD_IN_CAR"));
    }

    @Test
    @DisplayName("a company still in onboarding cannot use operational settings")
    void unapprovedCompanyCannotReadSettings() throws Exception {

        TaxiCompany company = fixtures.company(
                VerificationStatus.DRAFT, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH));

        mockMvc.perform(
                        get("/api/v1/partner/operational-settings")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("at least one payment method is required")
    void atLeastOnePaymentMethodRequired() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();

        mockMvc.perform(
                        put("/api/v1/partner/operational-settings")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"bookingEnabled":true,"paymentMethods":[]}
                                        """)
                )
                .andExpect(status().isBadRequest());
    }
}
