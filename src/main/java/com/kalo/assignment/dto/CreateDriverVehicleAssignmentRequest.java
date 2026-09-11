package com.kalo.assignment.dto;

import jakarta.validation.constraints.NotNull;

public record CreateDriverVehicleAssignmentRequest(

        @NotNull(message = "Driver id is required")
        Long driverId,

        @NotNull(message = "Vehicle id is required")
        Long vehicleId

) {
}