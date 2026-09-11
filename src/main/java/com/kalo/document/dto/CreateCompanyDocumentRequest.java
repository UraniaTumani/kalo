package com.kalo.document.dto;

import com.kalo.document.enums.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateCompanyDocumentRequest(

        @NotNull(message = "Document type is required")
        DocumentType documentType,

        @Size(max = 100)
        String documentNumber,

        @NotBlank(message = "File URL is required")
        @Size(max = 1000)
        String fileUrl,

        LocalDate issuedAt,

        LocalDate expiresAt

) {
}