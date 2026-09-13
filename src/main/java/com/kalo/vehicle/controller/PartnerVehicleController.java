package com.kalo.vehicle.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.vehicle.dto.CreateVehicleRequest;
import com.kalo.vehicle.dto.UpdateVehicleRequest;
import com.kalo.vehicle.dto.VehicleResponse;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.service.VehicleService;
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
@RequestMapping("/api/v1/partner/vehicles")
@RequiredArgsConstructor
public class PartnerVehicleController {

    private final VehicleService vehicleService;

    @PostMapping
    public ResponseEntity<VehicleResponse> createVehicle(
            @Valid
            @RequestBody
            CreateVehicleRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        vehicleService
                                .createVehicle(request)
                );
    }

    @GetMapping
    public ResponseEntity<Page<VehicleResponse>>
    getVehicles(
            @RequestParam(required = false)
            VehicleStatus status,

            /** Excludes vehicles a driver already holds; see the driver endpoint. */
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
                vehicleService.getVehicles(
                        status,
                        unassigned,
                        pageable
                )
        );
    }

    @GetMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse>
    getVehicleById(
            @PathVariable Long vehicleId
    ) {

        return ResponseEntity.ok(
                vehicleService
                        .getVehicleById(vehicleId)
        );
    }

    @PutMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse>
    updateVehicle(
            @PathVariable Long vehicleId,
            @Valid
            @RequestBody
            UpdateVehicleRequest request
    ) {

        return ResponseEntity.ok(
                vehicleService.updateVehicle(
                        vehicleId,
                        request
                )
        );
    }

    @DeleteMapping("/{vehicleId}")
    public ResponseEntity<Void>
    deleteVehicle(
            @PathVariable Long vehicleId
    ) {

        vehicleService.deleteVehicle(
                vehicleId
        );

        return ResponseEntity
                .noContent()
                .build();
    }
}