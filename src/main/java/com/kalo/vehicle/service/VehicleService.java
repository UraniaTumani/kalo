package com.kalo.vehicle.service;

import com.kalo.vehicle.dto.CreateVehicleRequest;
import com.kalo.vehicle.dto.UpdateVehicleRequest;
import com.kalo.vehicle.dto.VehicleResponse;

import java.util.List;

public interface VehicleService {

    VehicleResponse createVehicle(
            CreateVehicleRequest request
    );

    List<VehicleResponse> getVehicles();

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