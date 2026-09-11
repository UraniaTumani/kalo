package com.kalo.assignment.repository;

import com.kalo.assignment.entity.DriverVehicleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface DriverVehicleAssignmentRepository
        extends JpaRepository<DriverVehicleAssignment, Long> {

    Optional<DriverVehicleAssignment>
    findByDriverIdAndActiveTrue(Long driverId);

    Optional<DriverVehicleAssignment>
    findByVehicleIdAndActiveTrue(Long vehicleId);

    Optional<DriverVehicleAssignment>
    findByIdAndDriverCompanyId(
            Long assignmentId,
            Long companyId
    );

    List<DriverVehicleAssignment>
    findAllByDriverCompanyId(Long companyId);


    @Query("""
        SELECT a
        FROM DriverVehicleAssignment a
        JOIN FETCH a.vehicle
        WHERE a.driver.id = :driverId
        AND a.active = true
        """)
    Optional<DriverVehicleAssignment>
    findActiveAssignmentWithVehicle(
            @Param("driverId") Long driverId
    );
}