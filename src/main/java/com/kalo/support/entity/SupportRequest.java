package com.kalo.support.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.support.enums.SupportCategory;
import com.kalo.support.enums.SupportStatus;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
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

/**
 * One message from a customer or a partner to whoever runs KALO.
 *
 * A leaf of the model on purpose: it points at a user and nothing else. No
 * ride, no company, no driver — a support request is about the service, not
 * part of it, and giving it a foreign key into the ride tables would make the
 * ride flow answerable to a help desk.
 *
 * The submitter's role is copied onto the row rather than read back through
 * the user. An admin triaging a queue wants to know who was writing at the
 * time; a customer who later registers a company should not have their old
 * tickets silently reclassified.
 */
@Entity
@Table(name = "support_requests")
@Getter
@Setter
public class SupportRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SupportCategory category;

    @Column(nullable = false, length = 150)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SupportStatus status = SupportStatus.OPEN;
}
