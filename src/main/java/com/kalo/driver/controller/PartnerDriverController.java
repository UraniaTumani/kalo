package com.kalo.driver.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.driver.dto.CreateDriverRequest;
import com.kalo.driver.dto.DriverResponse;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.dto.UpdateDriverAvailabilityRequest;
import com.kalo.driver.dto.UpdateDriverRequest;
import com.kalo.driver.service.DriverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Partner - Fleet")
@RestController
@RequestMapping("/api/v1/partner/drivers")
@RequiredArgsConstructor
public class PartnerDriverController {

    private final DriverService driverService;

    @PostMapping
    public ResponseEntity<DriverResponse> createDriver(
            @Valid
            @RequestBody
            CreateDriverRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        driverService
                                .createDriver(request)
                );
    }

    @GetMapping
    public ResponseEntity<Page<DriverResponse>>
    getDrivers(
            @RequestParam(required = false)
            DriverStatus status,

            @RequestParam(required = false)
            DriverAvailabilityStatus availabilityStatus,

            /**
             * Excludes drivers that already hold a vehicle. The assignment
             * picker needs this, and the rule belongs here rather than being
             * reconstructed in the browser from a list of every assignment.
             */
            @RequestParam(required = false)
            Boolean unassigned,

            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                driverService.getDrivers(
                        status,
                        availabilityStatus,
                        unassigned,
                        pageable
                )
        );
    }

    @GetMapping("/{driverId}")
    public ResponseEntity<DriverResponse>
    getDriverById(
            @PathVariable Long driverId
    ) {

        return ResponseEntity.ok(
                driverService
                        .getDriverById(driverId)
        );
    }


    @PatchMapping("/{driverId}/availability")
    public ResponseEntity<DriverResponse>
    updateAvailability(
            @PathVariable Long driverId,
            @Valid
            @RequestBody
            UpdateDriverAvailabilityRequest request
    ) {

        return ResponseEntity.ok(
                driverService.updateAvailability(
                        driverId,
                        request
                )
        );
    }

    @PutMapping("/{driverId}")
    public ResponseEntity<DriverResponse>
    updateDriver(
            @PathVariable Long driverId,
            @Valid
            @RequestBody
            UpdateDriverRequest request
    ) {

        return ResponseEntity.ok(
                driverService.updateDriver(
                        driverId,
                        request
                )
        );
    }

    @DeleteMapping("/{driverId}")
    public ResponseEntity<Void>
    deleteDriver(
            @PathVariable Long driverId
    ) {

        driverService.deleteDriver(driverId);

        return ResponseEntity
                .noContent()
                .build();
    }
}