package com.kalo.driver.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(
        name = "drivers",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_drivers_phone",
                        columnNames = "phone"
                ),
                @UniqueConstraint(
                        name = "uk_drivers_license_number",
                        columnNames = "license_number"
                )
        },
        indexes = {
                @Index(
                        name = "idx_drivers_company_id",
                        columnList = "company_id"
                ),
                @Index(
                        name = "idx_drivers_status",
                        columnList = "status"
                )
        }
)
public class Driver extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "company_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_drivers_company"
            )
    )
    private TaxiCompany company;

    @Column(
            name = "first_name",
            nullable = false,
            length = 100
    )
    private String firstName;


    @Enumerated(EnumType.STRING)
    @Column(
            name = "availability_status",
            nullable = false,
            length = 30
    )
    private DriverAvailabilityStatus availabilityStatus;

    @Column(
            name = "last_name",
            nullable = false,
            length = 100
    )
    private String lastName;

    @Column(
            nullable = false,
            length = 30
    )
    private String phone;





    @Column(
            name = "license_number",
            nullable = false,
            length = 100
    )
    private String licenseNumber;

    @Column(
            name = "license_expiry_date",
            nullable = false
    )
    private LocalDate licenseExpiryDate;

    @Column(
            name = "date_of_birth"
    )
    private LocalDate dateOfBirth;

    @Column(
            name = "rating"
    )
    private Double rating;
    @Column(
            name = "rating_count",
            nullable = false
    )
    private Integer ratingCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 30
    )
    private DriverStatus status;
}