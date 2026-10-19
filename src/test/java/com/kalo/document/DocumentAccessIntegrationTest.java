package com.kalo.document;

import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.PaymentMethod;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Private company document permissions")
class DocumentAccessIntegrationTest extends AbstractIntegrationTest {

    private MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file", "licence.pdf", "application/pdf",
                "%PDF-1.7\\nprivate".getBytes(StandardCharsets.UTF_8)
        );
    }

    private TaxiCompany draftCompany() {
        return fixtures.company(
                VerificationStatus.DRAFT, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH)
        );
    }

    @Test
    @DisplayName("partner uploads and downloads a private PDF, but another partner cannot read it")
    void uploadsOnlyForOwningCompany() throws Exception {
        TaxiCompany owner = draftCompany();
        TaxiCompany other = draftCompany();
        String ownerToken = tokenFor(owner.getOwner());
        String otherToken = tokenFor(other.getOwner());

        String response = mockMvc.perform(
                multipart("/api/v1/partner/documents/upload")
                        .file(pdf())
                        .param("documentType", "TAXI_LICENSE")
                        .header("Authorization", bearer(ownerToken))
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileUrl").value(org.hamcrest.Matchers.startsWith("local:")))
                .andReturn().getResponse().getContentAsString();

        long id = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.id")).longValue();
        mockMvc.perform(get("/api/v1/partner/documents/" + id + "/file")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Content-Type-Options", "nosniff"));

        mockMvc.perform(get("/api/v1/partner/documents/" + id + "/file")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/admin/partners/" + owner.getId()
                        + "/documents/" + id + "/file")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/partners/" + owner.getId()
                        + "/documents/" + id + "/file")
                        .header("Authorization", bearer(tokenFor(fixtures.admin()))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/partners/" + other.getId()
                        + "/documents/" + id + "/file")
                        .header("Authorization", bearer(tokenFor(fixtures.admin()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("anonymous and customer callers cannot upload company documents")
    void uploadNeedsPartnerRole() throws Exception {
        mockMvc.perform(multipart("/api/v1/partner/documents/upload")
                        .file(pdf())
                        .param("documentType", "TAXI_LICENSE"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/api/v1/partner/documents/upload")
                        .file(pdf())
                        .param("documentType", "TAXI_LICENSE")
                        .header("Authorization", bearer(tokenFor(fixtures.customer()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("duplicate type and pending verification both reject uploads")
    void rejectsDuplicateAndPendingUploads() throws Exception {
        TaxiCompany company = draftCompany();
        String token = tokenFor(company.getOwner());
        fixtures.companyDocument(company, DocumentType.TAXI_LICENSE, DocumentVerificationStatus.DRAFT);

        mockMvc.perform(multipart("/api/v1/partner/documents/upload")
                        .file(pdf())
                        .param("documentType", "TAXI_LICENSE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        TaxiCompany pending = fixtures.company(
                VerificationStatus.PENDING, CompanyStatus.INACTIVE, true, Set.of(PaymentMethod.CASH)
        );
        mockMvc.perform(multipart("/api/v1/partner/documents/upload")
                        .file(pdf())
                        .param("documentType", "BUSINESS_REGISTRATION")
                        .header("Authorization", bearer(tokenFor(pending.getOwner()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an invalid document type cannot be uploaded to a company")
    void rejectsDriverDocumentAsCompanyDocument() throws Exception {
        TaxiCompany company = draftCompany();
        mockMvc.perform(multipart("/api/v1/partner/documents/upload")
                        .file(pdf())
                        .param("documentType", "DRIVING_LICENSE")
                        .header("Authorization", bearer(tokenFor(company.getOwner()))))
                .andExpect(status().isBadRequest());
    }
}
