package com.kalo.document.service;

import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.document.dto.DocumentResponse;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleDocumentService {

    private final DocumentRepository documentRepository;
    private final VehicleRepository vehicleRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final LocalDocumentStorage localDocumentStorage;

    @Transactional
    public DocumentResponse upload(Long vehicleId, DocumentType documentType,
                                   String documentNumber, LocalDate issuedAt,
                                   LocalDate expiresAt, MultipartFile file) {
        requireOwnedVehicle(vehicleId);
        if (documentType != DocumentType.VEHICLE_REGISTRATION
                && documentType != DocumentType.INSURANCE
                && documentType != DocumentType.PASSENGER_INSURANCE
                && documentType != DocumentType.TECHNICAL_INSPECTION
                && documentType != DocumentType.TAXIMETER_VERIFICATION) {
            throw new InvalidOperationException("Invalid vehicle document type");
        }
        if (issuedAt != null && expiresAt != null && expiresAt.isBefore(issuedAt)) {
            throw new InvalidOperationException("Document expiry date cannot be before issue date");
        }
        if (documentNumber != null && documentNumber.length() > 100) {
            throw new InvalidOperationException("Document number is too long");
        }
        if (documentRepository.existsByOwnerTypeAndOwnerIdAndDocumentType(
                DocumentOwnerType.VEHICLE, vehicleId, documentType)) {
            throw new ConflictException("Document of type " + documentType + " already exists");
        }

        String reference = localDocumentStorage.store(file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    localDocumentStorage.discard(reference);
                }
            }
        });

        Document document = new Document();
        document.setOwnerType(DocumentOwnerType.VEHICLE);
        document.setOwnerId(vehicleId);
        document.setDocumentType(documentType);
        document.setDocumentNumber(documentNumber == null || documentNumber.isBlank()
                ? null : documentNumber.trim());
        document.setFileUrl(reference);
        document.setIssuedAt(issuedAt);
        document.setExpiresAt(expiresAt);
        document.setVerificationStatus(DocumentVerificationStatus.DRAFT);
        return toResponse(documentRepository.save(document));
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(Long vehicleId) {
        requireOwnedVehicle(vehicleId);
        return documentRepository.findAllByOwnerTypeAndOwnerId(DocumentOwnerType.VEHICLE, vehicleId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public LocalDocumentStorage.StoredDocument download(Long vehicleId, Long documentId) {
        requireOwnedVehicle(vehicleId);
        Document document = documentRepository.findByIdAndOwnerTypeAndOwnerId(
                documentId, DocumentOwnerType.VEHICLE, vehicleId
        ).orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        return localDocumentStorage.read(document.getFileUrl());
    }

    private void requireOwnedVehicle(Long vehicleId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResourceNotFoundException("Vehicle not found");
        }
        var company = taxiCompanyRepository.findByOwnerPhone(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Taxi company not found"));
        vehicleRepository.findByIdAndCompanyId(vehicleId, company.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
    }

    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                document.getId(), document.getDocumentType(), document.getDocumentNumber(),
                document.getFileUrl(), document.getIssuedAt(), document.getExpiresAt(),
                document.getVerificationStatus(), document.getRejectionReason()
        );
    }
}
