package com.kalo.ride.controller;

import com.kalo.ride.dto.DriverCurrentRideResponse;
import com.kalo.ride.service.DriverCurrentRideService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DriverCurrentRideController {
    private final DriverCurrentRideService rides;
    @GetMapping("/api/v1/driver/me/ride")
    public ResponseEntity<DriverCurrentRideResponse> current() {
        return rides.current().map(ride -> ResponseEntity.ok().header("Cache-Control", "no-store").body(ride))
                .orElseGet(() -> ResponseEntity.noContent().header("Cache-Control", "no-store").build());
    }
}
