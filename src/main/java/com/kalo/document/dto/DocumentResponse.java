package com.kalo.document.dto;

import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;

import java.time.LocalDate;

public record DocumentResponse(
        Long id,
        DocumentType documentType,
        String documentNumber,
        String fileUrl,
        LocalDate issuedAt,
        LocalDate expiresAt,
        DocumentVerificationStatus verificationStatus,
        String rejectionReason
) {
}