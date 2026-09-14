package com.kalo.notification.dto;

import com.kalo.notification.enums.NotificationType;

import java.time.Instant;

public record CompanyNotificationResponse(

        Long id,

        NotificationType type,

        Long rideId,

        String message,

        boolean read,

        Instant createdAt

) {
}
