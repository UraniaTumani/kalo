package com.kalo.ride.controller;

import com.kalo.ride.dto.GuestAvailabilityRequest;
import com.kalo.ride.dto.GuestAvailabilityResponse;
import com.kalo.ride.service.GuestAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public")
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicAvailabilityController {

    private final GuestAvailabilityService guestAvailabilityService;

    /**
     * POST rather than GET so the visitor's coordinates travel in the body
     * instead of a URL that would be kept in access logs and proxy caches.
     */
    @SecurityRequirements
    @Operation(
            summary = "Taxi companies available near a location",
            description = """
                    Open to anonymous visitors. Read-only: no ride request and
                    no offers are created, so nothing here can be booked.
                    Register and use POST /api/v1/rides/search to actually
                    request a ride.
                    """
    )
    @PostMapping("/taxi-availability")
    public ResponseEntity<GuestAvailabilityResponse> checkAvailability(
            @Valid @RequestBody GuestAvailabilityRequest request
    ) {

        return ResponseEntity.ok(
                guestAvailabilityService.checkAvailability(request)
        );
    }
}
