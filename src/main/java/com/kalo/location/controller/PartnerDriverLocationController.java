package com.kalo.location.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.location.dto.UpdateDriverLocationRequest;
import com.kalo.location.service.DriverLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Partner - Fleet")
@RestController
@RequestMapping(
        "/api/v1/partner/drivers/{driverId}/location"
)
@RequiredArgsConstructor
public class PartnerDriverLocationController {

    private final DriverLocationService driverLocationService;

    @PutMapping
    public ResponseEntity<DriverLocationResponse>
    updateLocation(
            @PathVariable Long driverId,
            @Valid
            @RequestBody
            UpdateDriverLocationRequest request
    ) {

        return ResponseEntity.ok(
                driverLocationService
                        .updateLocation(
                                driverId,
                                request
                        )
        );
    }

    @GetMapping
    public ResponseEntity<DriverLocationResponse>
    getLocation(
            @PathVariable Long driverId
    ) {

        return ResponseEntity.ok(
                driverLocationService
                        .getLocation(driverId)
        );
    }
}