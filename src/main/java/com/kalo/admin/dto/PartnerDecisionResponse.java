package com.kalo.admin.dto;

import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;

public record PartnerDecisionResponse(

        Long companyId,

        VerificationStatus verificationStatus,

        CompanyStatus companyStatus,

        String message

) {
}