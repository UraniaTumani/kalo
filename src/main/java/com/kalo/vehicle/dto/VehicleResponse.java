package com.kalo.vehicle.dto;

import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.enums.VehicleType;

import java.time.LocalDate;

public record VehicleResponse(

        Long id,

        String plateNumber,

        String brand,

        String model,

        Integer manufactureYear,

        Integer seats,

        VehicleType vehicleType,

        LocalDate registrationExpiryDate,

        LocalDate insuranceExpiryDate,

        LocalDate technicalInspectionExpiryDate,

        VehicleStatus status

) {
}