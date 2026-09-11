package com.kalo.partner.controller;

import com.kalo.partner.dto.OperationalSettingsResponse;
import com.kalo.partner.dto.UpdateOperationalSettingsRequest;
import com.kalo.partner.service.PartnerOperationalSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
        "/api/v1/partner/operational-settings"
)
@RequiredArgsConstructor
public class PartnerOperationalSettingsController {

    private final PartnerOperationalSettingsService
            operationalSettingsService;

    @GetMapping
    public ResponseEntity<OperationalSettingsResponse>
    getSettings() {

        return ResponseEntity.ok(
                operationalSettingsService
                        .getSettings()
        );
    }

    @PutMapping
    public ResponseEntity<OperationalSettingsResponse>
    updateSettings(
            @Valid
            @RequestBody
            UpdateOperationalSettingsRequest request
    ) {

        return ResponseEntity.ok(
                operationalSettingsService
                        .updateSettings(
                                request
                        )
        );
    }
}