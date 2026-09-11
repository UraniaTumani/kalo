package com.kalo.document.service;

import com.kalo.document.dto.CreateCompanyDocumentRequest;
import com.kalo.document.dto.DocumentResponse;

import java.util.List;

public interface DocumentService {

    DocumentResponse createCompanyDocument(
            CreateCompanyDocumentRequest request
    );

    List<DocumentResponse> getCurrentCompanyDocuments();

    void deleteCurrentCompanyDocument(Long documentId);
}