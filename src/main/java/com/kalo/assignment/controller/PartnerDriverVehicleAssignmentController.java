package com.kalo.assignment.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.assignment.dto.CreateDriverVehicleAssignmentRequest;
import com.kalo.assignment.dto.DriverVehicleAssignmentResponse;
import com.kalo.assignment.service.DriverVehicleAssignmentService;
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
@RequestMapping(
        "/api/v1/partner/driver-vehicle-assignments"
)
@RequiredArgsConstructor
public class PartnerDriverVehicleAssignmentController {

    private final DriverVehicleAssignmentService assignmentService;

    @PostMapping
    public ResponseEntity<DriverVehicleAssignmentResponse>
    assignVehicle(
            @Valid
            @RequestBody
            CreateDriverVehicleAssignmentRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        assignmentService
                                .assignVehicle(request)
                );
    }

    @GetMapping
    public ResponseEntity<Page<DriverVehicleAssignmentResponse>>
    getAssignments(
            @RequestParam(required = false)
            Boolean active,

            @PageableDefault(
                    size = 20,
                    sort = "assignedFrom",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                assignmentService.getAssignments(
                        active,
                        pageable
                )
        );
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void>
    unassignVehicle(
            @PathVariable Long assignmentId
    ) {

        assignmentService
                .unassignVehicle(
                        assignmentId
                );

        return ResponseEntity
                .noContent()
                .build();
    }
}