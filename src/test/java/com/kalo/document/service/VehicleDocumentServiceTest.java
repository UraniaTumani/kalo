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

    @Test
    void unauthenticatedAccessCannotDownloadVehicleDocuments() {
        SecurityContextHolder.clearContext();

        assertThrows(ResourceNotFoundException.class, () -> service.download(1L, 2L));

        verifyNoInteractions(taxiCompanyRepository, vehicleRepository,
                documentRepository, localDocumentStorage);
    }
}
