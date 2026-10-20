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
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
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
public class DriverDocumentService {

    private final DocumentRepository documentRepository;
    private final DriverRepository driverRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final LocalDocumentStorage localDocumentStorage;

    @Transactional
    public DocumentResponse upload(Long driverId, DocumentType documentType,
                                   String documentNumber, LocalDate issuedAt,
                                   LocalDate expiresAt, MultipartFile file) {
        requireOwnedDriver(driverId);
        if (documentType != DocumentType.DRIVING_LICENSE
                && documentType != DocumentType.PROFESSIONAL_CERTIFICATE) {
            throw new InvalidOperationException("Invalid driver document type");
        }
        if (issuedAt != null && expiresAt != null && expiresAt.isBefore(issuedAt)) {
            throw new InvalidOperationException("Document expiry date cannot be before issue date");
        }
        if (documentNumber != null && documentNumber.length() > 100) {
            throw new InvalidOperationException("Document number is too long");
        }
        if (documentRepository.existsByOwnerTypeAndOwnerIdAndDocumentType(
                DocumentOwnerType.DRIVER, driverId, documentType)) {
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
        document.setOwnerType(DocumentOwnerType.DRIVER);
        document.setOwnerId(driverId);
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
    public List<DocumentResponse> list(Long driverId) {
        requireOwnedDriver(driverId);
        return documentRepository.findAllByOwnerTypeAndOwnerId(DocumentOwnerType.DRIVER, driverId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public LocalDocumentStorage.StoredDocument download(Long driverId, Long documentId) {
        requireOwnedDriver(driverId);
        Document document = documentRepository.findByIdAndOwnerTypeAndOwnerId(
                documentId, DocumentOwnerType.DRIVER, driverId
        ).orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        return localDocumentStorage.read(document.getFileUrl());
    }

    private void requireOwnedDriver(Long driverId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResourceNotFoundException("Driver not found");
        }
        var company = taxiCompanyRepository.findByOwnerPhone(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Taxi company not found"));
        driverRepository.findByIdAndCompanyId(driverId, company.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
    }

    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                document.getId(), document.getDocumentType(), document.getDocumentNumber(),
                document.getFileUrl(), document.getIssuedAt(), document.getExpiresAt(),
                document.getVerificationStatus(), document.getRejectionReason()
        );
    }
}
