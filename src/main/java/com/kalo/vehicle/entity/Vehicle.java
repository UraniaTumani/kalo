package com.kalo.vehicle.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.enums.VehicleType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(
        name = "vehicles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_vehicles_plate_number",
                        columnNames = "plate_number"
                )
        },
        indexes = {
                @Index(
                        name = "idx_vehicles_company_id",
                        columnList = "company_id"
                ),
                @Index(
                        name = "idx_vehicles_status",
                        columnList = "status"
                ),
                @Index(
                        name = "idx_vehicles_type",
                        columnList = "vehicle_type"
                )
        }
)
public class Vehicle extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "company_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_vehicles_company"
            )
    )
    private TaxiCompany company;

    @Column(
            name = "plate_number",
            nullable = false,
            length = 30
    )
    private String plateNumber;

    @Column(
            nullable = false,
            length = 100
    )
    private String brand;

    @Column(
            nullable = false,
            length = 100
    )
    private String model;

    @Column(
            name = "manufacture_year",
            nullable = false
    )
    private Integer manufactureYear;

    @Column(
            nullable = false
    )
    private Integer seats;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "vehicle_type",
            nullable = false,
            length = 30
    )
    private VehicleType vehicleType;

    @Column(
            name = "registration_expiry_date"
    )
    private LocalDate registrationExpiryDate;

    @Column(
            name = "insurance_expiry_date"
    )
    private LocalDate insuranceExpiryDate;

    @Column(
            name = "technical_inspection_expiry_date"
    )
    private LocalDate technicalInspectionExpiryDate;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 30
    )
    private VehicleStatus status;
}