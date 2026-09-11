package com.kalo.admin.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.admin.dto.AdminRideDetailResponse;
import com.kalo.admin.dto.AdminRideResponse;
import com.kalo.admin.service.AdminRideService;
import com.kalo.ride.enums.RideStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/rides")
@RequiredArgsConstructor
public class AdminRideController {

    private final AdminRideService adminRideService;

    @GetMapping
    public ResponseEntity<Page<AdminRideResponse>>
    getRides(
            @RequestParam(required = false)
            RideStatus status,

            @RequestParam(required = false)
            Long companyId,

            @PageableDefault(
                    size = 20,
                    sort = "requestedAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                adminRideService.getRides(
                        status,
                        companyId,
                        pageable
                )
        );
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<AdminRideDetailResponse>
    getRideById(
            @PathVariable Long rideId
    ) {

        return ResponseEntity.ok(
                adminRideService.getRideById(rideId)
        );
    }
}
