package com.kalo.ride.controller;

import com.kalo.ride.dto.AcceptRideRequest;
import com.kalo.ride.dto.CompleteRideRequest;
import com.kalo.ride.dto.PartnerRideResponse;
import com.kalo.ride.dto.RideResponse;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.service.RideService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/partner/rides")
@RequiredArgsConstructor
public class PartnerRideController {

    private final RideService rideService;

    @GetMapping
    public ResponseEntity<Page<PartnerRideResponse>>
    getRides(
            @RequestParam(required = false)
            RideStatus status,

            @PageableDefault(
                    size = 20,
                    sort = "requestedAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                rideService.getPartnerRides(
                        status,
                        pageable
                )
        );
    }

    @PostMapping("/{rideId}/accept")
    public ResponseEntity<RideResponse>
    acceptRide(
            @PathVariable Long rideId,

            @Valid
            @RequestBody
            AcceptRideRequest request
    ) {

        return ResponseEntity.ok(
                rideService.acceptRide(
                        rideId,
                        request
                )
        );
    }
    @PostMapping("/{rideId}/driver-arriving")
    public ResponseEntity<RideResponse>
    markDriverArriving(
            @PathVariable Long rideId
    ) {

        return ResponseEntity.ok(
                rideService.markDriverArriving(
                        rideId
                )
        );
    }

    @PostMapping("/{rideId}/driver-arrived")
    public ResponseEntity<RideResponse>
    markDriverArrived(
            @PathVariable Long rideId
    ) {

        return ResponseEntity.ok(
                rideService.markDriverArrived(
                        rideId
                )
        );
    }

    @PostMapping("/{rideId}/start")
    public ResponseEntity<RideResponse>
    startRide(
            @PathVariable Long rideId
    ) {

        return ResponseEntity.ok(
                rideService.startRide(
                        rideId
                )
        );
    }

    @PostMapping("/{rideId}/complete")
    public ResponseEntity<RideResponse>
    completeRide(
            @PathVariable Long rideId,

            @Valid
            @RequestBody
            CompleteRideRequest request
    ) {

        return ResponseEntity.ok(
                rideService.completeRide(
                        rideId,
                        request
                )
        );
    }
    @PostMapping("/{rideId}/decline")
    public ResponseEntity<RideResponse>
    declineRide(
            @PathVariable Long rideId
    ) {

        return ResponseEntity.ok(
                rideService.declineRide(
                        rideId
                )
        );
    }
}