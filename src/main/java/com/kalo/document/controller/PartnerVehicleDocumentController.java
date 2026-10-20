package com.kalo.document.controller;

import com.kalo.document.dto.DocumentResponse;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.service.LocalDocumentStorage;
import com.kalo.document.service.VehicleDocumentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Partner - Vehicle Documents")
@RestController
@RequestMapping("/api/v1/partner/vehicles/{vehicleId}/documents")
@RequiredArgsConstructor
public class PartnerVehicleDocumentController {

    private final VehicleDocumentService vehicleDocumentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable Long vehicleId,
            @RequestParam DocumentType documentType,
            @RequestParam(required = false) String documentNumber,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedAt,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresAt,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                vehicleDocumentService.upload(vehicleId, documentType, documentNumber,
                        issuedAt, expiresAt, file)
        );
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(vehicleDocumentService.list(vehicleId));
    }

    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> download(
            @PathVariable Long vehicleId,
            @PathVariable Long documentId
    ) {
        LocalDocumentStorage.StoredDocument stored =
                vehicleDocumentService.download(vehicleId, documentId);
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
}
