package com.kalo.assignment.service;

import com.kalo.assignment.dto.CreateDriverVehicleAssignmentRequest;
import com.kalo.assignment.dto.DriverVehicleAssignmentResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface DriverVehicleAssignmentService {

    DriverVehicleAssignmentResponse assignVehicle(
            CreateDriverVehicleAssignmentRequest request
    );

    Page<DriverVehicleAssignmentResponse> getAssignments(
            Boolean active,
            Pageable pageable
    );

    void unassignVehicle(
            Long assignmentId
    );
}