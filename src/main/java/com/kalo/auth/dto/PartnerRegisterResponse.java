package com.kalo.auth.dto;

import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.user.enums.UserStatus;

public record PartnerRegisterResponse(

        Long userId,

        Long companyId,

        String firstName,

        String lastName,

        String phone,

        String legalName,

        String displayName,

        String nipt,

        UserStatus userStatus,

        VerificationStatus verificationStatus,

        CompanyStatus companyStatus

) {
}