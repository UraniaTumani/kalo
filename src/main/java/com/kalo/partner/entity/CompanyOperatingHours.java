package com.kalo.partner.entity;

import com.kalo.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Table(
        name = "company_operating_hours",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_company_operating_hours_day",
                        columnNames = {
                                "company_id",
                                "day_of_week"
                        }
                )
        }
)
@Getter
@Setter
public class CompanyOperatingHours extends BaseEntity {

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "company_id",
            nullable = false
    )
    private TaxiCompany company;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "day_of_week",
            nullable = false,
            length = 20
    )
    private DayOfWeek dayOfWeek;

    @Column(
            name = "open_time"
    )
    private LocalTime openTime;

    @Column(
            name = "close_time"
    )
    private LocalTime closeTime;

    @Column(
            name = "closed",
            nullable = false
    )
    private boolean closed;
}