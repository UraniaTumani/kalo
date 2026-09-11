package com.kalo.admin.controller;

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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/partners")
@RequiredArgsConstructor
public class AdminPartnerController {

    private final AdminPartnerService adminPartnerService;

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