package com.kalo.admin.controller;

import com.kalo.admin.dto.AdminPartnerDetailResponse;
import com.kalo.admin.dto.AdminPartnerResponse;
import com.kalo.admin.dto.PartnerDecisionResponse;
import com.kalo.admin.dto.RejectPartnerRequest;
import com.kalo.admin.service.AdminPartnerService;
import com.kalo.partner.enums.VerificationStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/partners")
@RequiredArgsConstructor
public class AdminPartnerController {

    private final AdminPartnerService adminPartnerService;

    @GetMapping
    public ResponseEntity<List<AdminPartnerResponse>>
    getPartners(
            @RequestParam(required = false)
            VerificationStatus status
    ) {

        return ResponseEntity.ok(
                adminPartnerService
                        .getPartners(status)
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
}