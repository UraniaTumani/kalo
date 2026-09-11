package com.kalo.ride.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.driver.entity.Driver;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.ride.enums.RideStatus;
import com.kalo.user.entity.User;
import com.kalo.vehicle.entity.Vehicle;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "rides",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_rides_ride_request",
                        columnNames = "ride_request_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_rides_customer",
                        columnList = "customer_id"
                ),
                @Index(
                        name = "idx_rides_company",
                        columnList = "company_id"
                ),
                @Index(
                        name = "idx_rides_status",
                        columnList = "status"
                )
        }
)
public class Ride extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ride_request_id",
            nullable = false
    )
    private RideRequest rideRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_rides_customer"
            )
    )
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "company_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_rides_company"
            )
    )
    private TaxiCompany company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "driver_id",
            foreignKey = @ForeignKey(
                    name = "fk_rides_driver"
            )
    )
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "vehicle_id",
            foreignKey = @ForeignKey(
                    name = "fk_rides_vehicle"
            )
    )
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 30
    )
    private RideStatus status;

    @Column(
            name = "requested_at",
            nullable = false
    )
    private Instant requestedAt;

    @Column(
            name = "accepted_at"
    )
    private Instant acceptedAt;

    @Column(
            name = "declined_at"
    )
    private Instant declinedAt;

    @Column(
            name = "cancelled_at"
    )
    private Instant cancelledAt;


    @Column(
            name = "driver_arriving_at"
    )
    private Instant driverArrivingAt;

    @Column(
            name = "driver_arrived_at"
    )
    private Instant driverArrivedAt;

    @Column(
            name = "started_at"
    )
    private Instant startedAt;

    @Column(
            name = "completed_at"
    )
    private Instant completedAt;

    @Column(
            name = "final_amount",
            precision = 12,
            scale = 2
    )
    private BigDecimal finalAmount;
}