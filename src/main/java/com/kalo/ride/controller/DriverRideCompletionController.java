package com.kalo.ride.controller;

import com.kalo.ride.dto.CompleteRideRequest;
import com.kalo.ride.service.DriverRideActionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class DriverRideCompletionController {
    private final DriverRideActionService actions;
    @PostMapping("/api/v1/driver/me/rides/{rideId}/complete")
    public ResponseEntity<DriverRideActionService.Result> complete(@PathVariable Long rideId,
            @Valid @RequestBody CompleteRideRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(actions.complete(rideId, request));
    }
}
