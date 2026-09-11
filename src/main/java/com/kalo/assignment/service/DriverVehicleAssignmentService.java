package com.kalo.assignment.service;

import com.kalo.assignment.dto.CreateDriverVehicleAssignmentRequest;
import com.kalo.assignment.dto.DriverVehicleAssignmentResponse;

import java.util.List;

public interface DriverVehicleAssignmentService {

    DriverVehicleAssignmentResponse assignVehicle(
            CreateDriverVehicleAssignmentRequest request
    );

    List<DriverVehicleAssignmentResponse>
    getAssignments();

    void unassignVehicle(
            Long assignmentId
    );
}