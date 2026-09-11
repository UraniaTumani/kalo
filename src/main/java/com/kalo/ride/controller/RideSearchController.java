package com.kalo.ride.controller;

import com.kalo.ride.dto.CreateRideRequest;
import com.kalo.ride.dto.RideSearchResponse;
import com.kalo.ride.service.RideSearchService;
import com.kalo.ride.service.RideService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.kalo.ride.dto.RideResponse;
import com.kalo.ride.dto.SelectTaxiOfferRequest;
import com.kalo.ride.service.RideService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
public class RideSearchController {

    private final RideSearchService rideSearchService;
    private final RideService rideService;

    @PostMapping("/search")
    public ResponseEntity<RideSearchResponse>
    searchTaxis(
            @Valid
            @RequestBody
            CreateRideRequest request
    ) {

        RideSearchResponse response =
                rideSearchService.searchTaxis(
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping(
            "/requests/{rideRequestId}/select"
    )
    public ResponseEntity<RideResponse>
    selectTaxi(
            @PathVariable Long rideRequestId,

            @Valid
            @RequestBody
            SelectTaxiOfferRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        rideService.selectTaxiOffer(
                                rideRequestId,
                                request
                        )
                );


    }

    @GetMapping("/current")
    public ResponseEntity<RideResponse>
    getCurrentRide() {

        return ResponseEntity.ok(
                rideService.getCurrentRide()
        );
    }

    @GetMapping("/history")
    public ResponseEntity<List<RideResponse>>
    getRideHistory() {

        return ResponseEntity.ok(
                rideService.getRideHistory()
        );
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<RideResponse>
    getRideById(
            @PathVariable Long rideId
    ) {

        return ResponseEntity.ok(
                rideService.getRideById(
                        rideId
                )
        );
    }

    @PostMapping("/{rideId}/cancel")
    public ResponseEntity<RideResponse>
    cancelRide(
            @PathVariable Long rideId
    ) {

        return ResponseEntity.ok(
                rideService.cancelRide(
                        rideId
                )
        );
    }
}