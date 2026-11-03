package com.kalo.document;

import com.kalo.document.entity.Document;
import com.kalo.document.enums.*;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.document.service.DocumentStorage;
import com.kalo.document.service.DocumentStorageUnavailableException;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class DocumentStorageFailureIntegrationTest extends AbstractIntegrationTest {
    @MockitoBean DocumentStorage storage;
    @Autowired DocumentRepository documents;

    @Test void authorizedDownloadGetsSafeNoStore503WhenProviderIsUnavailable() throws Exception {
        var company = fixtures.approvedCompany();
        var document = fixtures.companyDocument(company, DocumentType.TAXI_LICENSE, DocumentVerificationStatus.APPROVED);
        when(storage.read(document.getFileUrl())).thenThrow(new DocumentStorageUnavailableException());
        mockMvc.perform(get("/api/v1/partner/documents/"+document.getId()+"/file")
                .header("Authorization", bearer(tokenFor(company.getOwner()))))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("Document storage is temporarily unavailable"));
    }

    @Test void anotherPartnerIsRejectedBeforeAccessingObjectStorage() throws Exception {
        var company = fixtures.approvedCompany();
        var document = fixtures.companyDocument(company, DocumentType.TAXI_LICENSE, DocumentVerificationStatus.APPROVED);
        var other = fixtures.approvedCompany();
        mockMvc.perform(get("/api/v1/partner/documents/"+document.getId()+"/file")
                .header("Authorization", bearer(tokenFor(other.getOwner()))))
                .andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test void unavailableEvidenceCannotBeApprovedOrAttributed() throws Exception {
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        var document = new Document();
        document.setOwnerType(DocumentOwnerType.DRIVER); document.setOwnerId(driver.getId());
        document.setDocumentType(DocumentType.DRIVING_LICENSE); document.setFileUrl("s3:00000000-0000-0000-0000-000000000000.pdf");
        document.setExpiresAt(com.kalo.common.util.LicenseValidity.today().plusYears(1));
        document.setVerificationStatus(DocumentVerificationStatus.DRAFT); documents.saveAndFlush(document);
        when(storage.read(document.getFileUrl())).thenThrow(new DocumentStorageUnavailableException());
        var admin = fixtures.user(UserRole.ADMIN, UserStatus.ACTIVE);
        mockMvc.perform(post("/api/v1/admin/partners/"+company.getId()+"/fleet/DRIVER/"+driver.getId()+"/documents/"+document.getId()+"/approve")
                .header("Authorization", bearer(tokenFor(admin)))).andExpect(status().isServiceUnavailable());
        var saved = documents.findById(document.getId()).orElseThrow();
        assertThat(saved.getVerificationStatus()).isEqualTo(DocumentVerificationStatus.DRAFT);
        assertThat(saved.getReviewedByUserId()).isNull();
        assertThat(saved.getReviewedAt()).isNull();
    }
}
