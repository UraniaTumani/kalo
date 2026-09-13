package com.kalo.vehicle.service;

import com.kalo.vehicle.dto.CreateVehicleRequest;
import com.kalo.vehicle.dto.UpdateVehicleRequest;
import com.kalo.vehicle.dto.VehicleResponse;
import com.kalo.vehicle.enums.VehicleStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface VehicleService {

    VehicleResponse createVehicle(
            CreateVehicleRequest request
    );

    Page<VehicleResponse> getVehicles(
            VehicleStatus status,
            Boolean unassigned,
            Pageable pageable
    );

    VehicleResponse getVehicleById(
            Long vehicleId
    );

    VehicleResponse updateVehicle(
            Long vehicleId,
            UpdateVehicleRequest request
    );

    void deleteVehicle(
            Long vehicleId
    );
}