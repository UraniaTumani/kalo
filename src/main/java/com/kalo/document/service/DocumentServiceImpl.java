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

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl
        implements DocumentService {

    private final DocumentRepository documentRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;

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