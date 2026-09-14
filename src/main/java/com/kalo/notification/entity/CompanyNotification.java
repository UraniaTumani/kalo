package com.kalo.notification.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.notification.enums.NotificationType;
import com.kalo.partner.entity.TaxiCompany;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Something a taxi company should know about, kept until they have seen it.
 *
 * Stored rather than pushed. A company is not sitting in front of the screen
 * when a passenger rates a ride an hour after it finished, so a notification
 * that only exists in the moment is one nobody receives. This is a row they
 * find when they next look.
 *
 * Deliberately small: a type, the ride it concerns, and a rendered message.
 * No channels, no delivery receipts, no preferences — those are a product in
 * their own right and none of them are needed to tell a company somebody
 * rated them.
 */
@Entity
@Table(name = "company_notifications")
@Getter
@Setter
public class CompanyNotification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "company_id",
            nullable = false
    )
    private TaxiCompany company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    /**
     * The ride this is about, where there is one.
     *
     * A plain id rather than a relation: a notification is a record of
     * something that happened, and it should survive whatever happens to the
     * row it refers to rather than holding it hostage to a foreign key.
     */
    @Column(name = "ride_id")
    private Long rideId;

    @Column(nullable = false, length = 500)
    private String message;

    /** Null until the company has seen it. */
    @Column(name = "read_at")
    private Instant readAt;
}
