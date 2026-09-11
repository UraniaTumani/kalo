package com.kalo.rating.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.driver.entity.Driver;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.ride.entity.Ride;
import com.kalo.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "ride_ratings",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ride_ratings_ride",
                        columnNames = "ride_id"
                )
        }
)
@Getter
@Setter
public class RideRating extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ride_id",
            nullable = false
    )
    private Ride ride;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false
    )
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "company_id",
            nullable = false
    )
    private TaxiCompany company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "driver_id",
            nullable = false
    )
    private Driver driver;

    @Column(
            name = "driver_rating",
            nullable = false
    )
    private Integer driverRating;

    @Column(
            name = "company_rating",
            nullable = false
    )
    private Integer companyRating;

    @Column(
            name = "comment",
            length = 1000
    )
    private String comment;
}