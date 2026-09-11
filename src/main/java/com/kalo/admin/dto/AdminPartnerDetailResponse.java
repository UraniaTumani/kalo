package com.kalo.admin.dto;

import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.user.enums.UserStatus;

import java.time.LocalDate;
import java.util.List;

public record AdminPartnerDetailResponse(

        Long companyId,

        Long ownerUserId,

        String ownerFirstName,

        String ownerLastName,

        String legalName,

        String displayName,

        String nipt,

        String phone,

        String email,

        String address,

        String licenseNumber,

        LocalDate licenseExpiryDate,

        VerificationStatus verificationStatus,

        CompanyStatus companyStatus,

        UserStatus ownerStatus,

        List<AdminDocumentResponse> documents

) {
}