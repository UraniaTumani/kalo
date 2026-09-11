package com.kalo.partner.dto;

import com.kalo.partner.enums.VerificationStatus;

public record PartnerVerificationResponse(
        Long companyId,
        VerificationStatus verificationStatus,
        String message
) {
}