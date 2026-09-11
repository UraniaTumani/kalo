package com.kalo.document.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.document.dto.CreateCompanyDocumentRequest;
import com.kalo.document.dto.DocumentResponse;
import com.kalo.document.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Partner - Company")
@RestController
@RequestMapping(
        "/api/v1/partner/documents"
)
@RequiredArgsConstructor
public class PartnerDocumentController {

    private final DocumentService documentService;

    @PostMapping
    public ResponseEntity<DocumentResponse>
    createDocument(
            @Valid
            @RequestBody
            CreateCompanyDocumentRequest request
    ) {

        DocumentResponse response =
                documentService
                        .createCompanyDocument(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>>
    getDocuments() {

        return ResponseEntity.ok(
                documentService
                        .getCurrentCompanyDocuments()
        );
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable Long documentId
    ) {

        documentService
                .deleteCurrentCompanyDocument(
                        documentId
                );

        return ResponseEntity.noContent().build();
    }
}