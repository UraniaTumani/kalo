package com.kalo.vehicle.dto;

import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.enums.VehicleType;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record UpdateVehicleRequest(

        @NotBlank(message = "Plate number is required")
        @Size(max = 30)
        String plateNumber,

        @NotBlank(message = "Brand is required")
        @Size(max = 100)
        String brand,

        @NotBlank(message = "Model is required")
        @Size(max = 100)
        String model,

        @NotNull(message = "Manufacture year is required")
        @Min(value = 1900)
        Integer manufactureYear,

        @NotNull(message = "Seats are required")
        @Min(value = 2)
        @Max(value = 20)
        Integer seats,

        @NotNull(message = "Vehicle type is required")
        VehicleType vehicleType,

        LocalDate registrationExpiryDate,

        LocalDate insuranceExpiryDate,

        LocalDate technicalInspectionExpiryDate,

        @NotNull(message = "Vehicle status is required")
        VehicleStatus status

) {
}