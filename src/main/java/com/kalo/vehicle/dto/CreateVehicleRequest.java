package com.kalo.vehicle.dto;

import com.kalo.vehicle.enums.VehicleType;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record CreateVehicleRequest(

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
        @Min(value = 1900, message = "Manufacture year is not valid")
        Integer manufactureYear,

        @NotNull(message = "Seats are required")
        @Min(value = 2, message = "Vehicle must have at least 2 seats")
        @Max(value = 20, message = "Seat number is not valid")
        Integer seats,

        @NotNull(message = "Vehicle type is required")
        VehicleType vehicleType,

        LocalDate registrationExpiryDate,

        LocalDate insuranceExpiryDate,

        LocalDate technicalInspectionExpiryDate

) {
}