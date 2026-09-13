package com.kalo.vehicle.repository;

import com.kalo.vehicle.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import com.kalo.vehicle.enums.VehicleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRepository
        extends JpaRepository<Vehicle, Long> {

    /**
     * Company scoping lives in the query for the same reason as drivers: a
     * page parameter must never become a way into another company's vehicles.
     */
    @Query("""
            SELECT v
            FROM Vehicle v
            WHERE v.company.id = :companyId
              AND (:status IS NULL OR v.status = :status)
              AND (:unassigned IS NULL OR :unassigned = FALSE OR NOT EXISTS (
                    SELECT 1
                    FROM DriverVehicleAssignment a
                    WHERE a.vehicle.id = v.id
                      AND a.active = TRUE
                  ))
            """)
    Page<Vehicle> findAllByCompanyIdFiltered(
            @Param("companyId") Long companyId,
            @Param("status") VehicleStatus status,
            @Param("unassigned") Boolean unassigned,
            Pageable pageable
    );

    Optional<Vehicle> findByIdAndCompanyId(
            Long vehicleId,
            Long companyId
    );

    boolean existsByPlateNumber(String plateNumber);
}