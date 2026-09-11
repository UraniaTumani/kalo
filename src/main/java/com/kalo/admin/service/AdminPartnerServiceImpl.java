package com.kalo.admin.service;

import com.kalo.admin.dto.AdminDocumentResponse;
import com.kalo.admin.dto.AdminPartnerDetailResponse;
import com.kalo.admin.dto.AdminPartnerResponse;
import com.kalo.admin.dto.PartnerDecisionResponse;
import com.kalo.admin.dto.RejectPartnerRequest;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPartnerServiceImpl
        implements AdminPartnerService {

    private final TaxiCompanyRepository taxiCompanyRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminPartnerResponse> getPartners(
            VerificationStatus status,
            CompanyStatus companyStatus,
            Pageable pageable
    ) {

        return taxiCompanyRepository
                .findAllByOptionalStatuses(
                        status,
                        companyStatus,
                        pageable
                )
                .map(this::mapToPartnerResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPartnerDetailResponse getPartnerById(
            Long companyId
    ) {

        TaxiCompany company =
                getCompany(companyId);

        List<Document> documents =
                documentRepository
                        .findAllByOwnerTypeAndOwnerId(
                                DocumentOwnerType.COMPANY,
                                company.getId()
                        );

        List<AdminDocumentResponse> documentResponses =
                documents
                        .stream()
                        .map(this::mapToDocumentResponse)
                        .toList();

        User owner = company.getOwner();

        return new AdminPartnerDetailResponse(
                company.getId(),
                owner.getId(),
                owner.getFirstName(),
                owner.getLastName(),
                company.getLegalName(),
                company.getDisplayName(),
                company.getNipt(),
                company.getPhone(),
                company.getEmail(),
                company.getAddress(),
                company.getLicenseNumber(),
                company.getLicenseExpiryDate(),
                company.getVerificationStatus(),
                company.getStatus(),
                owner.getStatus(),
                documentResponses
        );
    }

    @Override
    @Transactional
    public PartnerDecisionResponse approvePartner(
            Long companyId
    ) {

        TaxiCompany company =
                getCompany(companyId);

        validateCompanyCanBeReviewed(company);

        List<Document> documents =
                getCompanyDocuments(company);

        if (documents.isEmpty()) {
            throw new InvalidOperationException(
                    "Company has no documents to approve"
            );
        }

        for (Document document : documents) {

            if (document.getVerificationStatus()
                    != DocumentVerificationStatus.PENDING) {

                throw new InvalidOperationException(
                        "All company documents must be pending before approval"
                );
            }
        }

        company.setVerificationStatus(
                VerificationStatus.APPROVED
        );

        company.setStatus(
                CompanyStatus.ACTIVE
        );

        for (Document document : documents) {

            document.setVerificationStatus(
                    DocumentVerificationStatus.APPROVED
            );

            document.setRejectionReason(null);
        }

        User owner = company.getOwner();

        owner.setStatus(
                UserStatus.ACTIVE
        );

        taxiCompanyRepository.save(company);

        documentRepository.saveAll(documents);

        userRepository.save(owner);

        return new PartnerDecisionResponse(
                company.getId(),
                company.getVerificationStatus(),
                company.getStatus(),
                "Partner approved successfully"
        );
    }

    @Override
    @Transactional
    public PartnerDecisionResponse rejectPartner(
            Long companyId,
            RejectPartnerRequest request
    ) {

        TaxiCompany company =
                getCompany(companyId);

        validateCompanyCanBeReviewed(company);

        List<Document> documents =
                getCompanyDocuments(company);

        String rejectionReason =
                request.reason().trim();

        company.setVerificationStatus(
                VerificationStatus.REJECTED
        );

        company.setStatus(
                CompanyStatus.INACTIVE
        );

        for (Document document : documents) {

            if (document.getVerificationStatus()
                    == DocumentVerificationStatus.PENDING) {

                document.setVerificationStatus(
                        DocumentVerificationStatus.REJECTED
                );

                document.setRejectionReason(
                        rejectionReason
                );
            }
        }

        /*
         * IMPORTANT:
         *
         * We intentionally keep the owner user as PENDING.
         *
         * The partner must still be able to login,
         * modify the rejected application,
         * replace documents and submit again.
         */

        taxiCompanyRepository.save(company);

        documentRepository.saveAll(documents);

        return new PartnerDecisionResponse(
                company.getId(),
                company.getVerificationStatus(),
                company.getStatus(),
                "Partner rejected successfully"
        );
    }

    /**
     * A suspended company disappears from taxi search and can no longer accept
     * rides, because both paths require CompanyStatus.ACTIVE. Its drivers,
     * vehicles and historical rides are left untouched.
     */
    @Override
    @Transactional
    public PartnerDecisionResponse suspendPartner(
            Long companyId
    ) {

        TaxiCompany company =
                getCompany(companyId);

        if (company.getStatus() == CompanyStatus.SUSPENDED) {

            throw new InvalidOperationException(
                    "Taxi company is already suspended"
            );
        }

        company.setStatus(
                CompanyStatus.SUSPENDED
        );

        taxiCompanyRepository.save(company);

        log.info(
                "Taxi company suspended by admin: companyId={}",
                company.getId()
        );

        return new PartnerDecisionResponse(
                company.getId(),
                company.getVerificationStatus(),
                company.getStatus(),
                "Taxi company suspended successfully"
        );
    }

    @Override
    @Transactional
    public PartnerDecisionResponse reactivatePartner(
            Long companyId
    ) {

        TaxiCompany company =
                getCompany(companyId);

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Only an approved taxi company can be activated"
            );
        }

        if (company.getStatus() == CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company is already active"
            );
        }

        company.setStatus(
                CompanyStatus.ACTIVE
        );

        taxiCompanyRepository.save(company);

        log.info(
                "Taxi company reactivated by admin: companyId={}",
                company.getId()
        );

        return new PartnerDecisionResponse(
                company.getId(),
                company.getVerificationStatus(),
                company.getStatus(),
                "Taxi company reactivated successfully"
        );
    }

    private TaxiCompany getCompany(
            Long companyId
    ) {

        return taxiCompanyRepository
                .findById(companyId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Taxi company not found"
                        )
                );
    }

    private List<Document> getCompanyDocuments(
            TaxiCompany company
    ) {

        return documentRepository
                .findAllByOwnerTypeAndOwnerId(
                        DocumentOwnerType.COMPANY,
                        company.getId()
                );
    }

    private void validateCompanyCanBeReviewed(
            TaxiCompany company
    ) {

        if (company.getVerificationStatus()
                != VerificationStatus.PENDING) {

            throw new InvalidOperationException(
                    "Only a pending company can be reviewed"
            );
        }
    }

    private AdminPartnerResponse mapToPartnerResponse(
            TaxiCompany company
    ) {

        return new AdminPartnerResponse(
                company.getId(),
                company.getLegalName(),
                company.getDisplayName(),
                company.getNipt(),
                company.getPhone(),
                company.getEmail(),
                company.getVerificationStatus(),
                company.getStatus()
        );
    }

    private AdminDocumentResponse mapToDocumentResponse(
            Document document
    ) {

        return new AdminDocumentResponse(
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