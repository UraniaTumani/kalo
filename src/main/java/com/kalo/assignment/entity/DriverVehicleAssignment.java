package com.kalo.assignment.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.driver.entity.Driver;
import com.kalo.vehicle.entity.Vehicle;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "driver_vehicle_assignments",
        indexes = {
                @Index(
                        name = "idx_driver_vehicle_assignments_driver",
                        columnList = "driver_id"
                ),
                @Index(
                        name = "idx_driver_vehicle_assignments_vehicle",
                        columnList = "vehicle_id"
                ),
                @Index(
                        name = "idx_driver_vehicle_assignments_active",
                        columnList = "active"
                )
        }
)
public class DriverVehicleAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "driver_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_driver_vehicle_assignment_driver"
            )
    )
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "vehicle_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_driver_vehicle_assignment_vehicle"
            )
    )
    private Vehicle vehicle;

    @Column(
            name = "assigned_from",
            nullable = false
    )
    private Instant assignedFrom;

    @Column(
            name = "assigned_until"
    )
    private Instant assignedUntil;

    @Column(
            nullable = false
    )
    private boolean active;
}