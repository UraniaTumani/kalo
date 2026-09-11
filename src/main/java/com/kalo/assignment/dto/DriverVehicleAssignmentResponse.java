package com.kalo.assignment.dto;

import java.time.Instant;

public record DriverVehicleAssignmentResponse(

        Long assignmentId,

        Long driverId,

        String driverName,

        Long vehicleId,

        String plateNumber,

        String vehicleDescription,

        Instant assignedFrom,

        Instant assignedUntil,

        boolean active

) {
}