package com.kalo.document.service;

import com.kalo.document.dto.CreateCompanyDocumentRequest;
import com.kalo.document.dto.DocumentResponse;

import java.util.List;
import java.time.LocalDate;
import com.kalo.document.enums.DocumentType;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    DocumentResponse createCompanyDocument(
            CreateCompanyDocumentRequest request
    );

    DocumentResponse uploadCompanyDocument(DocumentType documentType, String documentNumber,
            LocalDate issuedAt, LocalDate expiresAt, MultipartFile file);

    LocalDocumentStorage.StoredDocument getCurrentCompanyDocumentFile(Long documentId);

    LocalDocumentStorage.StoredDocument getCompanyDocumentFileForAdmin(Long companyId, Long documentId);

    List<DocumentResponse> getCurrentCompanyDocuments();

    void deleteCurrentCompanyDocument(Long documentId);
}