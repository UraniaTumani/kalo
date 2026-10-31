package com.kalo.admin.dto;

import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import java.time.LocalDate;

/** Deliberately excludes storage references; bytes use the authorized file endpoint. */
public record FleetDocumentReviewResponse(Long id, DocumentType documentType, String documentNumber,
        LocalDate issuedAt, LocalDate expiresAt, DocumentVerificationStatus verificationStatus,
        String rejectionReason) {}
