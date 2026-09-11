package com.kalo.partner.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.partner.dto.PartnerProfileResponse;
import com.kalo.partner.dto.UpdatePartnerProfileRequest;
import com.kalo.partner.service.PartnerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.kalo.partner.dto.PartnerVerificationResponse;

@Tag(name = "Partner - Company")
@RestController
@RequestMapping("/api/v1/partner")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerService partnerService;

    @GetMapping("/me")
    public ResponseEntity<PartnerProfileResponse> getMyProfile() {

        return ResponseEntity.ok(
                partnerService.getCurrentPartnerProfile()
        );
    }

    @PutMapping("/me")
    public ResponseEntity<PartnerProfileResponse> updateMyProfile(
            @Valid @RequestBody UpdatePartnerProfileRequest request
    ) {

        return ResponseEntity.ok(
                partnerService.updateCurrentPartnerProfile(request)
        );
    }

    @PostMapping("/submit-verification")
    public ResponseEntity<PartnerVerificationResponse>
    submitForVerification() {

        return ResponseEntity.ok(
                partnerService.submitForVerification()
        );
    }
}