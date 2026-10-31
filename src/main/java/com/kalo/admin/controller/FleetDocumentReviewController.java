package com.kalo.admin.controller;

import com.kalo.admin.dto.FleetDocumentReviewResponse;
import com.kalo.admin.dto.RejectFleetDocumentRequest;
import com.kalo.admin.service.FleetDocumentReviewService;
import com.kalo.document.enums.DocumentOwnerType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/partners/{companyId}/fleet/{ownerType}/{ownerId}/documents")
public class FleetDocumentReviewController {
    private final FleetDocumentReviewService review;
    @GetMapping
    public ResponseEntity<List<FleetDocumentReviewResponse>> list(@PathVariable Long companyId,
            @PathVariable DocumentOwnerType ownerType, @PathVariable Long ownerId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(review.list(companyId, ownerType, ownerId));
    }
    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long companyId, @PathVariable DocumentOwnerType ownerType,
            @PathVariable Long ownerId, @PathVariable Long documentId) {
        var stored = review.file(companyId, ownerType, ownerId, documentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(stored.contentType()))
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename("document-" + documentId + "." + stored.extension()).build().toString())
                .body(stored.bytes());
    }
    @PostMapping("/{documentId}/approve")
    public ResponseEntity<FleetDocumentReviewResponse> approve(@PathVariable Long companyId,
            @PathVariable DocumentOwnerType ownerType, @PathVariable Long ownerId, @PathVariable Long documentId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(review.decide(companyId, ownerType, ownerId, documentId, true, null));
    }
    @PostMapping("/{documentId}/reject")
    public ResponseEntity<FleetDocumentReviewResponse> reject(@PathVariable Long companyId,
            @PathVariable DocumentOwnerType ownerType, @PathVariable Long ownerId, @PathVariable Long documentId,
            @Valid @RequestBody RejectFleetDocumentRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(review.decide(companyId, ownerType, ownerId, documentId, false, request.reason()));
    }
}
