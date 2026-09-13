package com.kalo.assignment.repository;

import com.kalo.assignment.entity.DriverVehicleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * Unassigning only flips the active flag, so this table keeps every
     * assignment a company has ever made. It is the one partner list that grows
     * without bound, which is why it is paged and why `active` is filterable.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT a
            FROM DriverVehicleAssignment a
            WHERE a.driver.company.id = :companyId
              AND (:active IS NULL OR a.active = :active)
            """)
    Page<DriverVehicleAssignment> findAllByDriverCompanyIdFiltered(
            @org.springframework.data.repository.query.Param("companyId") Long companyId,
            @org.springframework.data.repository.query.Param("active") Boolean active,
            Pageable pageable
    );


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