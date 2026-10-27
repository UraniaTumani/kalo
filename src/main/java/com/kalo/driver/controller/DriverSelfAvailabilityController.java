package com.kalo.driver.controller;

import com.kalo.driver.dto.UpdateDriverAvailabilityRequest;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.service.DriverSelfAvailabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DriverSelfAvailabilityController {
    private final DriverSelfAvailabilityService availability;
    public record Response(DriverAvailabilityStatus availabilityStatus) {}
    @PutMapping("/api/v1/driver/me/availability")
    public ResponseEntity<Response> update(@Valid @RequestBody UpdateDriverAvailabilityRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Response(availability.update(request.availabilityStatus())));
    }
}
