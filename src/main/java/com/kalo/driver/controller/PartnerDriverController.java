package com.kalo.driver.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.driver.dto.CreateDriverRequest;
import com.kalo.driver.dto.DriverResponse;
import com.kalo.driver.dto.UpdateDriverAvailabilityRequest;
import com.kalo.driver.dto.UpdateDriverRequest;
import com.kalo.driver.service.DriverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
    public ResponseEntity<List<DriverResponse>>
    getDrivers() {

        return ResponseEntity.ok(
                driverService.getDrivers()
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