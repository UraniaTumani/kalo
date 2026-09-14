package com.kalo.partner;

import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.PaymentMethod;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Partner onboarding and the company state machine")
class PartnerVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TaxiCompanyRepository taxiCompanyRepository;

    private TaxiCompany reload(TaxiCompany company) {
        return taxiCompanyRepository.findById(company.getId()).orElseThrow();
    }

    /* ------------------------------------------------------- registration */

    @Test
    @DisplayName("registration leaves the company DRAFT and INACTIVE")
    void registrationStartsAsDraft() throws Exception {

        String phone = fixtures.nextPhone();

        mockMvc.perform(
                        post("/api/v1/auth/register/partner")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName":"Arben","lastName":"Marku",
                                          "phone":"%s","email":"arben%s@kalo.test",
                                          "password":"%s",
                                          "legalName":"Arben Taxi SHPK","displayName":"Arben Taxi",
                                          "nipt":"K%sA","address":"Rruga Test, Tirane"
                                        }
                                        """.formatted(phone, fixtures.next(), TestDataFactory.PASSWORD, fixtures.next()))
                )
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.verificationStatus").value("DRAFT"))
                .andExpect(jsonPath("$.companyStatus").value("INACTIVE"));
    }

    /* ------------------------------------------------------- submit rules */

    @Test
    @DisplayName("submitting without a licence expiry is rejected")
    void submitRequiresLicenceExpiry() throws Exception {

        TaxiCompany company = fixtures.company(
                VerificationStatus.DRAFT, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH));

        company.setLicenseExpiryDate(null);
        taxiCompanyRepository.save(company);

        fixtures.companyDocument(company, DocumentType.BUSINESS_REGISTRATION, DocumentVerificationStatus.DRAFT);
        fixtures.companyDocument(company, DocumentType.TAXI_LICENSE, DocumentVerificationStatus.DRAFT);

        mockMvc.perform(
                        post("/api/v1/partner/submit-verification")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Taxi license expiry date is required"));

        assertThat(reload(company).getVerificationStatus()).isEqualTo(VerificationStatus.DRAFT);
    }

    @Test
    @DisplayName("submitting without the required documents is rejected")
    void submitRequiresDocuments() throws Exception {

        TaxiCompany company = fixtures.company(
                VerificationStatus.DRAFT, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH));

        mockMvc.perform(
                        post("/api/v1/partner/submit-verification")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().is4xxClientError());

        assertThat(reload(company).getVerificationStatus()).isEqualTo(VerificationStatus.DRAFT);
    }

    @Test
    @DisplayName("a complete company moves to PENDING on submit")
    void validSubmitMovesToPending() throws Exception {

        TaxiCompany company = draftReadyToSubmit();

        mockMvc.perform(
                        post("/api/v1/partner/submit-verification")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"));

        TaxiCompany saved = reload(company);
        assertThat(saved.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(saved.getStatus()).isEqualTo(CompanyStatus.INACTIVE);
    }

    /* ------------------------------------------------------ admin review */

    @Test
    @DisplayName("an admin cannot approve a DRAFT company")
    void adminCannotApproveDraft() throws Exception {

        TaxiCompany company = draftReadyToSubmit();
        String admin = tokenFor(fixtures.admin());

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/approve")
                                .header("Authorization", bearer(admin))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a pending company can be reviewed"));

        assertThat(reload(company).getVerificationStatus()).isEqualTo(VerificationStatus.DRAFT);
    }

    @Test
    @DisplayName("approving a PENDING company makes it APPROVED and ACTIVE")
    void approvePendingCompany() throws Exception {

        TaxiCompany company = pendingCompany();
        String admin = tokenFor(fixtures.admin());

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/approve")
                                .header("Authorization", bearer(admin))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.companyStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("rejecting a PENDING company makes it REJECTED and INACTIVE")
    void rejectPendingCompany() throws Exception {

        TaxiCompany company = pendingCompany();
        String admin = tokenFor(fixtures.admin());

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/reject")
                                .header("Authorization", bearer(admin))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"reason":"Licence document is unreadable"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("REJECTED"));

        assertThat(reload(company).getStatus()).isEqualTo(CompanyStatus.INACTIVE);
    }

    /**
     * The way back in after a rejection.
     *
     * A rejection names something the company has to fix, so it has to be
     * followed by a second attempt — otherwise the first mistake is permanent
     * and the only remedy is a new account. Nothing covered this path, even
     * though the service allows REJECTED alongside DRAFT as a submittable
     * state.
     */
    @Test
    @DisplayName("a rejected company can fix the problem and submit again")
    void rejectedCompanyCanResubmit() throws Exception {

        TaxiCompany company = pendingCompany();
        String admin = tokenFor(fixtures.admin());
        String partner = tokenFor(company.getOwner());

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/reject")
                                .header("Authorization", bearer(admin))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"reason":"Licence document is unreadable"}
                                        """)
                )
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/partner/submit-verification")
                                .header("Authorization", bearer(partner))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"));

        /* And the second review can approve, so the loop actually closes. */
        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/approve")
                                .header("Authorization", bearer(admin))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("APPROVED"));

        assertThat(reload(company).getStatus()).isEqualTo(CompanyStatus.ACTIVE);
    }

    @Test
    @DisplayName("a company already awaiting review cannot submit again")
    void pendingCompanyCannotResubmit() throws Exception {

        TaxiCompany company = pendingCompany();

        mockMvc.perform(
                        post("/api/v1/partner/submit-verification")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("an admin cannot approve a company that was already rejected")
    void adminCannotApproveRejected() throws Exception {

        TaxiCompany company = fixtures.company(
                VerificationStatus.REJECTED, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH));

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/approve")
                                .header("Authorization", bearer(tokenFor(fixtures.admin())))
                )
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------- suspend/reactivate */

    @Test
    @DisplayName("a company that was never approved cannot be suspended")
    void cannotSuspendUnapprovedCompany() throws Exception {

        TaxiCompany company = fixtures.company(
                VerificationStatus.DRAFT, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH));

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/suspend")
                                .header("Authorization", bearer(tokenFor(fixtures.admin())))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only an approved taxi company can be suspended"));

        // The regression that mattered: this must never become DRAFT + SUSPENDED,
        // a state no admin action could undo.
        assertThat(reload(company).getStatus()).isEqualTo(CompanyStatus.INACTIVE);
    }

    @Test
    @DisplayName("a PENDING company cannot be suspended either")
    void cannotSuspendPendingCompany() throws Exception {

        TaxiCompany company = pendingCompany();

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/suspend")
                                .header("Authorization", bearer(tokenFor(fixtures.admin())))
                )
                .andExpect(status().isBadRequest());

        assertThat(reload(company).getStatus()).isEqualTo(CompanyStatus.INACTIVE);
    }

    @Test
    @DisplayName("an approved and active company can be suspended, then reactivated")
    void suspendAndReactivateApprovedCompany() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        String admin = tokenFor(fixtures.admin());

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/suspend")
                                .header("Authorization", bearer(admin))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStatus").value("SUSPENDED"));

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/reactivate")
                                .header("Authorization", bearer(admin))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("a partner cannot approve their own company")
    void partnerCannotApproveOwnCompany() throws Exception {

        TaxiCompany company = pendingCompany();

        mockMvc.perform(
                        post("/api/v1/admin/partners/" + company.getId() + "/approve")
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                )
                .andExpect(status().isForbidden());
    }

    /* ------------------------------------------------------------ helpers */

    private TaxiCompany draftReadyToSubmit() {

        TaxiCompany company = fixtures.company(
                VerificationStatus.DRAFT, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH));

        fixtures.companyDocument(company, DocumentType.BUSINESS_REGISTRATION, DocumentVerificationStatus.DRAFT);
        fixtures.companyDocument(company, DocumentType.TAXI_LICENSE, DocumentVerificationStatus.DRAFT);

        return company;
    }

    /**
     * Submitted through the real endpoint, so the PENDING state is genuine.
     *
     * Returns the instance created here rather than a reloaded one: a reloaded
     * company holds its owner as a lazy proxy, which cannot be read outside a
     * session. Tests that care about current state call {@link #reload}.
     */
    private TaxiCompany pendingCompany() throws Exception {

        TaxiCompany company = draftReadyToSubmit();
        User owner = company.getOwner();

        mockMvc.perform(
                        post("/api/v1/partner/submit-verification")
                                .header("Authorization", bearer(tokenFor(owner)))
                )
                .andExpect(status().isOk());

        return company;
    }
}
