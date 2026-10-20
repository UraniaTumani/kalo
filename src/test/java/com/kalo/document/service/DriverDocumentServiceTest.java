package com.kalo.document.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.TaxiCompanyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriverDocumentServiceTest {

    private static final Long COMPANY_ID = 12L;
    private static final Long FOREIGN_DRIVER_ID = 99L;

    @Mock DocumentRepository documentRepository;
    @Mock DriverRepository driverRepository;
    @Mock TaxiCompanyRepository taxiCompanyRepository;
    @Mock LocalDocumentStorage localDocumentStorage;

    private DriverDocumentService service;

    @BeforeEach
    void setUp() {
        service = new DriverDocumentService(
                documentRepository, driverRepository, taxiCompanyRepository, localDocumentStorage);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("+355690000000", null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void foreignDriver() {
        TaxiCompany company = new TaxiCompany();
        ReflectionTestUtils.setField(company, "id", COMPANY_ID);
        when(taxiCompanyRepository.findByOwnerPhone("+355690000000"))
                .thenReturn(Optional.of(company));
        when(driverRepository.findByIdAndCompanyId(FOREIGN_DRIVER_ID, COMPANY_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    void partnerCannotUploadToAnotherCompanyDriver() {
        foreignDriver();
        MockMultipartFile file = new MockMultipartFile(
                "file", "licence.pdf", "application/pdf",
                "%PDF-1.7\nproof".getBytes(StandardCharsets.UTF_8));

        assertThrows(ResourceNotFoundException.class, () ->
                service.upload(FOREIGN_DRIVER_ID,
                        com.kalo.document.enums.DocumentType.DRIVING_LICENSE,
                        null, null, null, file));

        verifyNoInteractions(localDocumentStorage, documentRepository);
    }

    @Test
    void partnerCannotListAnotherCompanyDriverDocuments() {
        foreignDriver();
        assertThrows(ResourceNotFoundException.class, () -> service.list(FOREIGN_DRIVER_ID));
        verifyNoInteractions(documentRepository, localDocumentStorage);
    }

    @Test
    void partnerCannotDownloadAnotherCompanyDriverDocuments() {
        foreignDriver();
        assertThrows(ResourceNotFoundException.class, () ->
                service.download(FOREIGN_DRIVER_ID, 42L));
        verifyNoInteractions(documentRepository, localDocumentStorage);
    }


    private void ownedDriver(Long driverId) {
        TaxiCompany company = new TaxiCompany();
        ReflectionTestUtils.setField(company, "id", COMPANY_ID);
        when(taxiCompanyRepository.findByOwnerPhone("+355690000000"))
                .thenReturn(Optional.of(company));
        when(driverRepository.findByIdAndCompanyId(driverId, COMPANY_ID))
                .thenReturn(Optional.of(new com.kalo.driver.entity.Driver()));
    }

    @Test
    void uploadsDrivingLicenseForOwnedDriver() {
        verifiesSuccessfulUpload(com.kalo.document.enums.DocumentType.DRIVING_LICENSE);
    }

    @Test
    void uploadsProfessionalCertificateForOwnedDriver() {
        verifiesSuccessfulUpload(com.kalo.document.enums.DocumentType.PROFESSIONAL_CERTIFICATE);
    }

    private void verifiesSuccessfulUpload(com.kalo.document.enums.DocumentType documentType) {
        Long driverId = 17L;
        ownedDriver(driverId);
        byte[] bytes = "%PDF-1.7\nproof".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", bytes);
        String reference = "local:00000000-0000-0000-0000-000000000001.pdf";

        when(localDocumentStorage.store(file)).thenReturn(reference);
        when(documentRepository.save(any(com.kalo.document.entity.Document.class)))
                .thenAnswer(invocation -> {
                    com.kalo.document.entity.Document document = invocation.getArgument(0);
                    ReflectionTestUtils.setField(document, "id", 24L);
                    return document;
                });

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.upload(driverId, documentType, " CERT-123 ",
                    java.time.LocalDate.of(2025, 1, 1),
                    java.time.LocalDate.of(2028, 1, 1), file);

            org.junit.jupiter.api.Assertions.assertEquals(documentType, result.documentType());
            org.junit.jupiter.api.Assertions.assertEquals("CERT-123", result.documentNumber());
            org.junit.jupiter.api.Assertions.assertEquals(reference, result.fileUrl());

            var captor = org.mockito.ArgumentCaptor.forClass(com.kalo.document.entity.Document.class);
            verify(documentRepository).save(captor.capture());
            org.junit.jupiter.api.Assertions.assertEquals(DocumentOwnerType.DRIVER, captor.getValue().getOwnerType());
            org.junit.jupiter.api.Assertions.assertEquals(driverId, captor.getValue().getOwnerId());
            org.junit.jupiter.api.Assertions.assertEquals(
                    com.kalo.document.enums.DocumentVerificationStatus.DRAFT,
                    captor.getValue().getVerificationStatus());

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
            verify(localDocumentStorage, never()).discard(anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void unauthenticatedAccessCannotReadDocuments() {
        SecurityContextHolder.clearContext();
        assertThrows(ResourceNotFoundException.class, () -> service.download(1L, 2L));
        verifyNoInteractions(taxiCompanyRepository, driverRepository,
                documentRepository, localDocumentStorage);
    }
}
