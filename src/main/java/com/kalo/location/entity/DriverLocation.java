package com.kalo.location.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.driver.entity.Driver;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "driver_locations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_driver_locations_driver",
                        columnNames = "driver_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_driver_locations_driver",
                        columnList = "driver_id"
                ),
                @Index(
                        name = "idx_driver_locations_updated_at",
                        columnList = "location_updated_at"
                )
        }
)
public class DriverLocation extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "driver_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_driver_locations_driver"
            )
    )
    private Driver driver;

    @Column(
            nullable = false
    )
    private Double latitude;

    @Column(
            nullable = false
    )
    private Double longitude;

    @Column(
            name = "location_updated_at",
            nullable = false
    )
    private Instant locationUpdatedAt;
}