package com.kalo.partner.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.util.PhoneNumberNormalizer;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.partner.dto.PartnerProfileResponse;
import com.kalo.partner.dto.PartnerVerificationResponse;
import com.kalo.partner.dto.UpdatePartnerProfileRequest;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PartnerServiceImpl implements PartnerService {

    private final TaxiCompanyRepository taxiCompanyRepository;
    private final DocumentRepository documentRepository;

    @Override
    @Transactional(readOnly = true)
    public PartnerProfileResponse getCurrentPartnerProfile() {

        TaxiCompany company = getCurrentCompany();

        return mapToResponse(company);
    }

    @Override
    @Transactional
    public PartnerProfileResponse updateCurrentPartnerProfile(
            UpdatePartnerProfileRequest request
    ) {

        TaxiCompany company = getCurrentCompany();


        if (company.getVerificationStatus()
                == VerificationStatus.PENDING) {

            throw new InvalidOperationException(
                    "Company profile cannot be modified while verification is pending"
            );
        }

        company.setLegalName(
                request.legalName().trim()
        );

        company.setDisplayName(
                request.displayName().trim()
        );

        /*
         * The company contact number is not a login identifier and is not
         * unique, but it is normalised anyway so every phone in the system
         * reads the same way.
         */
        company.setPhone(
                PhoneNumberNormalizer.normalize(request.phone())
        );

        company.setEmail(
                request.email() == null || request.email().isBlank()
                        ? null
                        : request.email().trim().toLowerCase()
        );

        company.setAddress(
                request.address().trim()
        );

        company.setLicenseNumber(
                request.licenseNumber() == null
                        || request.licenseNumber().isBlank()
                        ? null
                        : request.licenseNumber().trim()
        );

        company.setLicenseExpiryDate(
                request.licenseExpiryDate()
        );

        TaxiCompany savedCompany =
                taxiCompanyRepository.save(company);

        return mapToResponse(savedCompany);
    }

    @Override
    @Transactional
    public PartnerVerificationResponse submitForVerification() {

        TaxiCompany company = getCurrentCompany();

        if (company.getVerificationStatus()
                != VerificationStatus.DRAFT
                && company.getVerificationStatus()
                != VerificationStatus.REJECTED) {

            throw new InvalidOperationException(
                    "Company cannot be submitted for verification in current status"
            );
        }

        validateCompanyProfile(company);

        Document businessRegistration =
                getRequiredDocument(
                        company.getId(),
                        DocumentType.BUSINESS_REGISTRATION
                );

        Document taxiLicense =
                getRequiredDocument(
                        company.getId(),
                        DocumentType.TAXI_LICENSE
                );

        validateDocument(businessRegistration);
        validateDocument(taxiLicense);

        company.setVerificationStatus(
                VerificationStatus.PENDING
        );

        company.setStatus(
                CompanyStatus.INACTIVE
        );

        businessRegistration.setVerificationStatus(
                DocumentVerificationStatus.PENDING
        );

        taxiLicense.setVerificationStatus(
                DocumentVerificationStatus.PENDING
        );

        taxiCompanyRepository.save(company);

        documentRepository.saveAll(
                List.of(
                        businessRegistration,
                        taxiLicense
                )
        );

        return new PartnerVerificationResponse(
                company.getId(),
                company.getVerificationStatus(),
                "Company submitted for verification successfully"
        );
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

    private void validateCompanyProfile(
            TaxiCompany company
    ) {

        if (isBlank(company.getLegalName())) {
            throw new InvalidOperationException(
                    "Legal company name is required"
            );
        }

        if (isBlank(company.getDisplayName())) {
            throw new InvalidOperationException(
                    "Display name is required"
            );
        }

        if (isBlank(company.getNipt())) {
            throw new InvalidOperationException(
                    "NIPT is required"
            );
        }

        if (isBlank(company.getPhone())) {
            throw new InvalidOperationException(
                    "Company phone is required"
            );
        }

        if (isBlank(company.getAddress())) {
            throw new InvalidOperationException(
                    "Company address is required"
            );
        }

        if (isBlank(company.getLicenseNumber())) {
            throw new InvalidOperationException(
                    "Taxi license number is required"
            );
        }

        if (company.getLicenseExpiryDate() == null) {
            throw new InvalidOperationException(
                    "Taxi license expiry date is required"
            );
        }

        if (company.getLicenseExpiryDate()
                .isBefore(LocalDate.now())) {

            throw new InvalidOperationException(
                    "Taxi license has expired"
            );
        }
    }

    private Document getRequiredDocument(
            Long companyId,
            DocumentType documentType
    ) {

        return documentRepository
                .findByOwnerTypeAndOwnerIdAndDocumentType(
                        DocumentOwnerType.COMPANY,
                        companyId,
                        documentType
                )
                .orElseThrow(() ->
                        new InvalidOperationException(
                                "Required document missing: "
                                        + documentType
                        )
                );
    }

    private void validateDocument(
            Document document
    ) {

        if (document.getFileUrl() == null
                || document.getFileUrl().isBlank()) {

            throw new InvalidOperationException(
                    "Document file is missing: "
                            + document.getDocumentType()
            );
        }

        if (document.getExpiresAt() != null
                && document.getExpiresAt()
                .isBefore(LocalDate.now())) {

            throw new InvalidOperationException(
                    "Document has expired: "
                            + document.getDocumentType()
            );
        }

        if (document.getVerificationStatus()
                == DocumentVerificationStatus.PENDING) {

            throw new InvalidOperationException(
                    "Document is already under review: "
                            + document.getDocumentType()
            );
        }

        if (document.getVerificationStatus()
                == DocumentVerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Document is already approved: "
                            + document.getDocumentType()
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private PartnerProfileResponse mapToResponse(
            TaxiCompany company
    ) {

        return new PartnerProfileResponse(
                company.getId(),
                company.getLegalName(),
                company.getDisplayName(),
                company.getNipt(),
                company.getPhone(),
                company.getEmail(),
                company.getAddress(),
                company.getLicenseNumber(),
                company.getLicenseExpiryDate(),
                company.getVerificationStatus(),
                company.getStatus()
        );
    }
}