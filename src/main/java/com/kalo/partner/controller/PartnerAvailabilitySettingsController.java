package com.kalo.partner.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.partner.dto.*;
import com.kalo.partner.service.PartnerAvailabilitySettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Partner - Company")
@RestController
@RequestMapping(
        "/api/v1/partner/availability-settings"
)
@RequiredArgsConstructor
public class PartnerAvailabilitySettingsController {

    private final PartnerAvailabilitySettingsService service;

    @GetMapping("/service-area")
    public ResponseEntity<ServiceAreaResponse>
    getServiceArea() {

        return ResponseEntity.ok(
                service.getServiceArea()
        );
    }

    @PutMapping("/service-area")
    public ResponseEntity<ServiceAreaResponse>
    updateServiceArea(
            @Valid
            @RequestBody
            UpdateServiceAreaRequest request
    ) {

        return ResponseEntity.ok(
                service.updateServiceArea(
                        request
                )
        );
    }

    @GetMapping("/operating-hours")
    public ResponseEntity<List<OperatingHoursResponse>>
    getOperatingHours() {

        return ResponseEntity.ok(
                service.getOperatingHours()
        );
    }

    @PutMapping("/operating-hours")
    public ResponseEntity<List<OperatingHoursResponse>>
    updateOperatingHours(
            @Valid
            @RequestBody
            UpdateOperatingHoursRequest request
    ) {

        return ResponseEntity.ok(
                service.updateOperatingHours(
                        request
                )
        );
    }
}