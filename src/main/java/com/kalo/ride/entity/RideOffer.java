package com.kalo.ride.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.driver.entity.Driver;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.vehicle.entity.Vehicle;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "ride_offers",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ride_offers_request_company",
                        columnNames = {
                                "ride_request_id",
                                "company_id"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_ride_offers_request",
                        columnList = "ride_request_id"
                ),
                @Index(
                        name = "idx_ride_offers_company",
                        columnList = "company_id"
                )
        }
)
public class RideOffer extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ride_request_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ride_offers_request"
            )
    )
    private RideRequest rideRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "company_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ride_offers_company"
            )
    )
    private TaxiCompany company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "nearest_driver_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ride_offers_driver"
            )
    )
    private Driver nearestDriver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "vehicle_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ride_offers_vehicle"
            )
    )
    private Vehicle vehicle;

    @Column(
            name = "distance_km",
            nullable = false
    )
    private Double distanceKm;
}