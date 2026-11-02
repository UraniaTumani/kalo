package com.kalo.document.controller;

import com.kalo.document.dto.DocumentResponse;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.service.FleetDocumentRenewalService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner")
public class FleetDocumentRenewalController {
    private final FleetDocumentRenewalService renewal;
    @PostMapping(value="/drivers/{ownerId}/documents/{documentId}/renew", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> driver(@PathVariable Long ownerId, @PathVariable Long documentId,
            @RequestParam(required=false) String documentNumber,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate issuedAt,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expiresAt,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(
                renewal.renew(DocumentOwnerType.DRIVER, ownerId, documentId, documentNumber, issuedAt, expiresAt, file));
    }
    @PostMapping(value="/vehicles/{ownerId}/documents/{documentId}/renew", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> vehicle(@PathVariable Long ownerId, @PathVariable Long documentId,
            @RequestParam(required=false) String documentNumber,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate issuedAt,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expiresAt,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(
                renewal.renew(DocumentOwnerType.VEHICLE, ownerId, documentId, documentNumber, issuedAt, expiresAt, file));
    }
    @GetMapping("/drivers/{ownerId}/documents/history")
    public ResponseEntity<List<DocumentResponse>> driverHistory(@PathVariable Long ownerId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(renewal.history(DocumentOwnerType.DRIVER,ownerId));
    }
    @GetMapping("/vehicles/{ownerId}/documents/history")
    public ResponseEntity<List<DocumentResponse>> vehicleHistory(@PathVariable Long ownerId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(renewal.history(DocumentOwnerType.VEHICLE,ownerId));
    }
}
