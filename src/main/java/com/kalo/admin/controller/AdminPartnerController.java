package com.kalo.admin.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.admin.dto.AdminPartnerDetailResponse;
import com.kalo.admin.dto.AdminPartnerResponse;
import com.kalo.admin.dto.PartnerDecisionResponse;
import com.kalo.admin.dto.RejectPartnerRequest;
import com.kalo.admin.service.AdminPartnerService;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import com.kalo.document.service.DocumentService;
import com.kalo.document.service.LocalDocumentStorage;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/partners")
@RequiredArgsConstructor
public class AdminPartnerController {

    private final AdminPartnerService adminPartnerService;
    private final DocumentService documentService;

    @GetMapping
    public ResponseEntity<Page<AdminPartnerResponse>>
    getPartners(
            @RequestParam(required = false)
            VerificationStatus status,

            @RequestParam(required = false)
            CompanyStatus companyStatus,

            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                adminPartnerService
                        .getPartners(
                                status,
                                companyStatus,
                                pageable
                        )
        );
    }

    @GetMapping("/{companyId}")
    public ResponseEntity<AdminPartnerDetailResponse>
    getPartnerById(
            @PathVariable Long companyId
    ) {

        return ResponseEntity.ok(
                adminPartnerService
                        .getPartnerById(companyId)
        );
    }

    @GetMapping("/{companyId}/documents/{documentId}/file")
    public ResponseEntity<byte[]> downloadCompanyDocument(
            @PathVariable Long companyId, @PathVariable Long documentId
    ) {
        LocalDocumentStorage.StoredDocument stored =
                documentService.getCompanyDocumentFileForAdmin(companyId, documentId);
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

    @PostMapping("/{companyId}/approve")
    public ResponseEntity<PartnerDecisionResponse>
    approvePartner(
            @PathVariable Long companyId
    ) {

        return ResponseEntity.ok(
                adminPartnerService
                        .approvePartner(companyId)
        );
    }

    @PostMapping("/{companyId}/reject")
    public ResponseEntity<PartnerDecisionResponse>
    rejectPartner(
            @PathVariable Long companyId,

            @Valid
            @RequestBody
            RejectPartnerRequest request
    ) {

        return ResponseEntity.ok(
                adminPartnerService
                        .rejectPartner(
                                companyId,
                                request
                        )
        );
    }

    @PostMapping("/{companyId}/suspend")
    public ResponseEntity<PartnerDecisionResponse>
    suspendPartner(
            @PathVariable Long companyId
    ) {

        return ResponseEntity.ok(
                adminPartnerService
                        .suspendPartner(companyId)
        );
    }

    @PostMapping("/{companyId}/reactivate")
    public ResponseEntity<PartnerDecisionResponse>
    reactivatePartner(
            @PathVariable Long companyId
    ) {

        return ResponseEntity.ok(
                adminPartnerService
                        .reactivatePartner(companyId)
        );
    }
}