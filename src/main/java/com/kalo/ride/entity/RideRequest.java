package com.kalo.ride.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.ride.enums.RideRequestStatus;
import com.kalo.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "ride_requests",
        indexes = {
                @Index(
                        name = "idx_ride_requests_customer",
                        columnList = "customer_id"
                ),
                @Index(
                        name = "idx_ride_requests_status",
                        columnList = "status"
                ),
                @Index(
                        name = "idx_ride_requests_expires_at",
                        columnList = "expires_at"
                )
        }
)
public class RideRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ride_requests_customer"
            )
    )
    private User customer;

    @Column(
            name = "pickup_latitude",
            nullable = false
    )
    private Double pickupLatitude;

    @Column(
            name = "pickup_longitude",
            nullable = false
    )
    private Double pickupLongitude;

    @Column(
            name = "pickup_address",
            length = 500
    )
    private String pickupAddress;

    @Column(
            name = "destination_latitude",
            nullable = false
    )
    private Double destinationLatitude;

    @Column(
            name = "destination_longitude",
            nullable = false
    )
    private Double destinationLongitude;

    @Column(
            name = "destination_address",
            length = 500
    )
    private String destinationAddress;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 30
    )
    private RideRequestStatus status;

    @Column(
            name = "expires_at",
            nullable = false
    )
    private Instant expiresAt;
}