package com.kalo.admin.dto;

import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;

public record AdminPartnerResponse(

        Long companyId,

        String legalName,

        String displayName,

        String nipt,

        String phone,

        String email,

        VerificationStatus verificationStatus,

        CompanyStatus companyStatus

) {
}