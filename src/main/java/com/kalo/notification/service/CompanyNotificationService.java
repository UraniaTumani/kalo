package com.kalo.notification.service;

import com.kalo.notification.dto.CompanyNotificationResponse;
import com.kalo.partner.entity.TaxiCompany;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CompanyNotificationService {

    /**
     * Records that a passenger rated a ride.
     *
     * Takes the company rather than looking it up, because the caller is
     * already holding it — and because a notification must never be the reason
     * a rating fails.
     */
    void rideRated(
            TaxiCompany company,
            Long rideId,
            int driverRating,
            int companyRating
    );

    /** The signed-in partner's own notifications, newest first. */
    Page<CompanyNotificationResponse> getMyNotifications(
            Pageable pageable
    );

    long unreadCount();

    CompanyNotificationResponse markRead(
            Long notificationId
    );
}
