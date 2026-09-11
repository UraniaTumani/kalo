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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminPartnerServiceImpl
        implements AdminPartnerService {

    private final TaxiCompanyRepository taxiCompanyRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPartnerResponse> getPartners(
            VerificationStatus status
    ) {

        List<TaxiCompany> companies;

        if (status == null) {
            companies = taxiCompanyRepository.findAll();
        } else {
            companies =
                    taxiCompanyRepository
                            .findByVerificationStatus(status);
        }

        return companies
                .stream()
                .map(this::mapToPartnerResponse)
                .toList();
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