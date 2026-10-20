package com.kalo.document.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.vehicle.repository.VehicleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleDocumentServiceTest {

    private static final Long COMPANY_ID = 12L;
    private static final Long FOREIGN_VEHICLE_ID = 99L;

    @Mock DocumentRepository documentRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock TaxiCompanyRepository taxiCompanyRepository;
    @Mock LocalDocumentStorage localDocumentStorage;

    private VehicleDocumentService service;

    @BeforeEach
    void setUp() {
        service = new VehicleDocumentService(
                documentRepository, vehicleRepository, taxiCompanyRepository, localDocumentStorage);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("+355690000000", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void foreignVehicle() {
        TaxiCompany company = new TaxiCompany();
        ReflectionTestUtils.setField(company, "id", COMPANY_ID);
        when(taxiCompanyRepository.findByOwnerPhone("+355690000000"))
                .thenReturn(Optional.of(company));
        when(vehicleRepository.findByIdAndCompanyId(FOREIGN_VEHICLE_ID, COMPANY_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    void partnerCannotUploadToAnotherCompanyVehicle() {
        foreignVehicle();
        MockMultipartFile file = new MockMultipartFile(
                "file", "registration.pdf", "application/pdf",
                "%PDF-1.7\\nproof".getBytes(StandardCharsets.UTF_8));

        assertThrows(ResourceNotFoundException.class, () ->
                service.upload(FOREIGN_VEHICLE_ID,
                        DocumentType.VEHICLE_REGISTRATION,
                        null, null, null, file));

        verifyNoInteractions(localDocumentStorage, documentRepository);
    }

    @Test
    void partnerCannotListAnotherCompanyVehicleDocuments() {
        foreignVehicle();

        assertThrows(ResourceNotFoundException.class, () -> service.list(FOREIGN_VEHICLE_ID));

        verifyNoInteractions(documentRepository, localDocumentStorage);
    }

    @Test
    void partnerCannotDownloadAnotherCompanyVehicleDocuments() {
        foreignVehicle();

        assertThrows(ResourceNotFoundException.class, () ->
                service.download(FOREIGN_VEHICLE_ID, 42L));

        verifyNoInteractions(documentRepository, localDocumentStorage);
    }


    private void ownedVehicle(Long vehicleId) {
        TaxiCompany company = new TaxiCompany();
        ReflectionTestUtils.setField(company, "id", COMPANY_ID);
        when(taxiCompanyRepository.findByOwnerPhone("+355690000000"))
                .thenReturn(Optional.of(company));
        when(vehicleRepository.findByIdAndCompanyId(vehicleId, COMPANY_ID))
                .thenReturn(Optional.of(new com.kalo.vehicle.entity.Vehicle()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = DocumentType.class,
            names = {"VEHICLE_REGISTRATION", "INSURANCE", "PASSENGER_INSURANCE",
                    "TECHNICAL_INSPECTION", "TAXIMETER_VERIFICATION"})
    void uploadsSupportedVehicleDocumentForOwnedVehicle(DocumentType documentType) {
        Long vehicleId = 17L;
        ownedVehicle(vehicleId);
        MockMultipartFile file = new MockMultipartFile(
                "file", "vehicle.pdf", "application/pdf",
                "%PDF-1.7\\nproof".getBytes(StandardCharsets.UTF_8));
        String reference = "local:00000000-0000-0000-0000-000000000002.pdf";

        when(localDocumentStorage.store(file)).thenReturn(reference);
        when(documentRepository.save(any(com.kalo.document.entity.Document.class)))
                .thenAnswer(invocation -> {
                    com.kalo.document.entity.Document document = invocation.getArgument(0);
                    ReflectionTestUtils.setField(document, "id", 25L);
                    return document;
                });

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.upload(vehicleId, documentType, " VEH-123 ",
                    java.time.LocalDate.of(2025, 1, 1),
                    java.time.LocalDate.of(2028, 1, 1), file);

            org.junit.jupiter.api.Assertions.assertEquals(documentType, result.documentType());
            org.junit.jupiter.api.Assertions.assertEquals("VEH-123", result.documentNumber());
            org.junit.jupiter.api.Assertions.assertEquals(reference, result.fileUrl());

            var captor = org.mockito.ArgumentCaptor.forClass(com.kalo.document.entity.Document.class);
            verify(documentRepository).save(captor.capture());
            org.junit.jupiter.api.Assertions.assertEquals(
                    com.kalo.document.enums.DocumentOwnerType.VEHICLE, captor.getValue().getOwnerType());
            org.junit.jupiter.api.Assertions.assertEquals(vehicleId, captor.getValue().getOwnerId());
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
    void unauthenticatedAccessCannotDownloadVehicleDocuments() {
        SecurityContextHolder.clearContext();

        assertThrows(ResourceNotFoundException.class, () -> service.download(1L, 2L));

        verifyNoInteractions(taxiCompanyRepository, vehicleRepository,
                documentRepository, localDocumentStorage);
    }
}
