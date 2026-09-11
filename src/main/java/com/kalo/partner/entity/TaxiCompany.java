package com.kalo.partner.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import com.kalo.partner.enums.PaymentMethod;

import java.util.HashSet;
import java.util.Set;
@Getter
@Setter
@Entity
@Table(
        name = "taxi_companies",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_taxi_companies_nipt",
                        columnNames = "nipt"
                ),
                @UniqueConstraint(
                        name = "uk_taxi_companies_owner_user",
                        columnNames = "owner_user_id"
                )
        }
)
public class TaxiCompany extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "owner_user_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_taxi_companies_owner_user"
            )
    )
    private User owner;

    @Column(
            name = "legal_name",
            nullable = false,
            length = 200
    )
    private String legalName;
    @Column(name = "service_center_latitude")
    private Double serviceCenterLatitude;

    @Column(name = "service_center_longitude")
    private Double serviceCenterLongitude;

    @Column(name = "service_radius_km")
    private Double serviceRadiusKm;

    @Column(
            name = "timezone",
            nullable = false,
            length = 50
    )
    private String timezone = "Europe/Tirane";
    @Column(
            name = "display_name",
            nullable = false,
            length = 150
    )
    private String displayName;

    @Column(
            nullable = false,
            length = 30
    )
    private String nipt;

    @Column(name = "rating")
    private Double rating;

    @Column(
            name = "rating_count",
            nullable = false
    )
    private Integer ratingCount = 0;

    @Column(
            name = "booking_enabled",
            nullable = false
    )
    private boolean bookingEnabled = true;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "taxi_company_payment_methods",
            joinColumns = @JoinColumn(
                    name = "company_id"
            )
    )
    @Enumerated(EnumType.STRING)
    @Column(
            name = "payment_method",
            nullable = false
    )
    private Set<PaymentMethod> paymentMethods =
            new HashSet<>();

    @Column(
            nullable = false,
            length = 30
    )
    private String phone;

    @Column(
            length = 255
    )
    private String email;

    @Column(
            nullable = false,
            length = 500
    )
    private String address;

    @Column(
            name = "license_number",
            length = 100
    )
    private String licenseNumber;

    @Column(
            name = "license_expiry_date"
    )
    private LocalDate licenseExpiryDate;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "verification_status",
            nullable = false,
            length = 30
    )
    private VerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 30
    )
    private CompanyStatus status;
}