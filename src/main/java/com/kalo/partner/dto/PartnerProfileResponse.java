package com.kalo.partner.dto;

import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;

import java.time.LocalDate;

public record PartnerProfileResponse(
        Long companyId,
        String legalName,
        String displayName,
        String nipt,
        String phone,
        String email,
        String address,
        String licenseNumber,
        LocalDate licenseExpiryDate,
        VerificationStatus verificationStatus,
        CompanyStatus status
) {
}