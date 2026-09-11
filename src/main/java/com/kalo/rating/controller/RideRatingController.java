package com.kalo.rating.controller;

import com.kalo.rating.dto.CreateRideRatingRequest;
import com.kalo.rating.dto.RideRatingResponse;
import com.kalo.rating.service.RideRatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
public class RideRatingController {

    private final RideRatingService rideRatingService;

    @PostMapping("/{rideId}/rating")
    public ResponseEntity<RideRatingResponse>
    rateRide(
            @PathVariable Long rideId,

            @Valid
            @RequestBody
            CreateRideRatingRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        rideRatingService.rateRide(
                                rideId,
                                request
                        )
                );
    }
}