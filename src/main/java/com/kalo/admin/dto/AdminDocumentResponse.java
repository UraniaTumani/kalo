package com.kalo.admin.dto;

import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;

import java.time.LocalDate;

public record AdminDocumentResponse(

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