package com.kalo.document.service;

import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.document.dto.CreateCompanyDocumentRequest;
import com.kalo.document.dto.DocumentResponse;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDate;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl
        implements DocumentService {

    private final DocumentRepository documentRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final LocalDocumentStorage localDocumentStorage;

    @Override
    @Transactional
    public DocumentResponse createCompanyDocument(
            CreateCompanyDocumentRequest request
    ) {

        TaxiCompany company = getCurrentCompany();

        if (company.getVerificationStatus()
                == VerificationStatus.PENDING) {

            throw new InvalidOperationException(
                    "Documents cannot be modified while company verification is pending"
            );
        }

        validateCompanyDocumentType(
                request.documentType()
        );

        if (request.issuedAt() != null
                && request.expiresAt() != null
                && request.expiresAt()
                .isBefore(request.issuedAt())) {

            throw new InvalidOperationException(
                    "Document expiry date cannot be before issue date"
            );
        }

        boolean alreadyExists =
                documentRepository
                        .existsByOwnerTypeAndOwnerIdAndDocumentType(
                                DocumentOwnerType.COMPANY,
                                company.getId(),
                                request.documentType()
                        );

        if (alreadyExists) {
            throw new ConflictException(
                    "Document of type "
                            + request.documentType()
                            + " already exists"
            );
        }

        Document document = new Document();

        document.setOwnerType(
                DocumentOwnerType.COMPANY
        );

        document.setOwnerId(
                company.getId()
        );

        document.setDocumentType(
                request.documentType()
        );

        document.setDocumentNumber(
                normalize(request.documentNumber())
        );

        document.setFileUrl(
                request.fileUrl().trim()
        );

        document.setIssuedAt(
                request.issuedAt()
        );

        document.setExpiresAt(
                request.expiresAt()
        );

        document.setVerificationStatus(
                DocumentVerificationStatus.DRAFT
        );

        Document saved =
                documentRepository.save(document);

        return mapToResponse(saved);
    }

    /**
     * Uploads a new company document. The existing URL-based API is kept for
     * legacy records; this is the preferred path for newly attached files.
     */
    @Override
    @Transactional
    public DocumentResponse uploadCompanyDocument(
            DocumentType documentType,
            String documentNumber,
            LocalDate issuedAt,
            LocalDate expiresAt,
            MultipartFile file
    ) {
        TaxiCompany company = getCurrentCompany();
        if (company.getVerificationStatus() == VerificationStatus.PENDING) {
            throw new InvalidOperationException(
                    "Documents cannot be modified while company verification is pending"
            );
        }
        if (documentType == null) {
            throw new InvalidOperationException("Document type is required");
        }
        validateCompanyDocumentType(documentType);
        if (issuedAt != null && expiresAt != null && expiresAt.isBefore(issuedAt)) {
            throw new InvalidOperationException("Document expiry date cannot be before issue date");
        }
        if (documentNumber != null && documentNumber.length() > 100) {
            throw new InvalidOperationException("Document number is too long");
        }
        if (documentRepository.existsByOwnerTypeAndOwnerIdAndDocumentType(
                DocumentOwnerType.COMPANY, company.getId(), documentType)) {
            throw new ConflictException("Document of type " + documentType + " already exists");
        }

        // Files are written only after permissions and duplicate checks.
        String storedReference = localDocumentStorage.store(file);
        Document document = new Document();
        document.setOwnerType(DocumentOwnerType.COMPANY);
        document.setOwnerId(company.getId());
        document.setDocumentType(documentType);
        document.setDocumentNumber(normalize(documentNumber));
        document.setFileUrl(storedReference);
        document.setIssuedAt(issuedAt);
        document.setExpiresAt(expiresAt);
        document.setVerificationStatus(DocumentVerificationStatus.DRAFT);
        return mapToResponse(documentRepository.save(document));
    }

    @Override
    @Transactional(readOnly = true)
    public LocalDocumentStorage.StoredDocument getCurrentCompanyDocumentFile(Long documentId) {
        TaxiCompany company = getCurrentCompany();
        Document document = documentRepository.findByIdAndOwnerTypeAndOwnerId(
                documentId, DocumentOwnerType.COMPANY, company.getId()
        ).orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        return localDocumentStorage.read(document.getFileUrl());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse>
    getCurrentCompanyDocuments() {

        TaxiCompany company = getCurrentCompany();

        return documentRepository
                .findAllByOwnerTypeAndOwnerId(
                        DocumentOwnerType.COMPANY,
                        company.getId()
                )
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteCurrentCompanyDocument(
            Long documentId
    ) {

        TaxiCompany company = getCurrentCompany();

        if (company.getVerificationStatus()
                == VerificationStatus.PENDING) {

            throw new InvalidOperationException(
                    "Documents cannot be deleted while company verification is pending"
            );
        }

        Document document = documentRepository
                .findByIdAndOwnerTypeAndOwnerId(
                        documentId,
                        DocumentOwnerType.COMPANY,
                        company.getId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Document not found"
                        )
                );

        if (document.getVerificationStatus()
                == DocumentVerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Approved document cannot be deleted"
            );
        }

        if (document.getVerificationStatus()
                == DocumentVerificationStatus.PENDING) {

            throw new InvalidOperationException(
                    "Document under review cannot be deleted"
            );
        }

        documentRepository.delete(document);
    }

    private TaxiCompany getCurrentCompany() {

        String phone = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        return taxiCompanyRepository
                .findByOwnerPhone(phone)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Taxi company not found for current partner"
                        )
                );
    }

    private void validateCompanyDocumentType(
            DocumentType documentType
    ) {

        if (documentType
                != DocumentType.BUSINESS_REGISTRATION
                && documentType
                != DocumentType.TAXI_LICENSE) {

            throw new InvalidOperationException(
                    "Document type "
                            + documentType
                            + " is not valid for a taxi company"
            );
        }
    }

    private String normalize(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private DocumentResponse mapToResponse(
            Document document
    ) {

        return new DocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getDocumentNumber(),
                document.getFileUrl(),
                document.getIssuedAt(),
                document.getExpiresAt(),
                document.getVerificationStatus(),
                document.getRejectionReason()
        );
    }
}