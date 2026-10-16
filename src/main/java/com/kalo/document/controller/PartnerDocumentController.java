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
import java.time.LocalDate;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.service.LocalDocumentStorage;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
            @RequestParam DocumentType documentType,
            @RequestParam(required = false) String documentNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresAt,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                documentService.uploadCompanyDocument(
                        documentType, documentNumber, issuedAt, expiresAt, file
                )
        );
    }

    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable Long documentId) {
        LocalDocumentStorage.StoredDocument stored =
                documentService.getCurrentCompanyDocumentFile(documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("document-" + documentId + "." + stored.extension())
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(stored.bytes());
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