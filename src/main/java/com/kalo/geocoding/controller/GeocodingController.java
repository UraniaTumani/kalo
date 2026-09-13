package com.kalo.geocoding.controller;

import com.kalo.geocoding.dto.PlaceResponse;
import com.kalo.geocoding.dto.ReverseResponse;
import com.kalo.geocoding.service.GeocodingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Address lookup, as seen by the booking page.
 *
 * Authenticated, like everything not under /public: only a signed-in customer
 * books a ride, and an open proxy to somebody else's geocoder is a thing people
 * find and use.
 */
@Tag(name = "Geocoding")
@Validated
@RestController
@RequestMapping("/api/v1/geocoding")
@RequiredArgsConstructor
public class GeocodingController {

    private final GeocodingService geocodingService;

    @GetMapping("/search")
    public ResponseEntity<List<PlaceResponse>> search(
            @RequestParam @NotBlank String q
    ) {

        return ResponseEntity.ok(
                geocodingService.search(q)
        );
    }

    @GetMapping("/reverse")
    public ResponseEntity<ReverseResponse> reverse(
            @RequestParam double lat,
            @RequestParam double lng
    ) {

        return ResponseEntity.ok(
                new ReverseResponse(
                        geocodingService.reverse(lat, lng)
                )
        );
    }
}
