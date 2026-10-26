package com.kalo.location.controller;

import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.location.dto.UpdateDriverLocationRequest;
import com.kalo.location.service.DriverSelfLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DriverSelfLocationController {
    private final DriverSelfLocationService locations;

    @PutMapping("/api/v1/driver/me/location")
    public ResponseEntity<DriverLocationResponse> update(@Valid @RequestBody UpdateDriverLocationRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(locations.update(request));
    }
}
