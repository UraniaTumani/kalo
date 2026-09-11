package com.kalo.vehicle.repository;

import com.kalo.vehicle.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository
        extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findAllByCompanyId(Long companyId);

    Optional<Vehicle> findByIdAndCompanyId(
            Long vehicleId,
            Long companyId
    );

    boolean existsByPlateNumber(String plateNumber);
}