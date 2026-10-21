package com.kalo.notification.scheduler;

import com.kalo.notification.service.DocumentExpiryNotificationSweepService;
import com.kalo.ride.scheduler.SweepLock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Intentionally OFF by default until F26.2 has passed DEV review.
 * Uses a separate PostgreSQL transaction advisory lock across instances.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.notifications.document-expiry.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class DocumentExpiryNotificationScheduler {

    private static final long DOCUMENT_EXPIRY_LOCK = 4_612_003L;

    private final DocumentExpiryNotificationSweepService service;
    private final SweepLock sweepLock;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT24H")
    public void sweep() {
        sweepLock.runExclusively(
                DOCUMENT_EXPIRY_LOCK,
                "Document expiry notifications",
                service::createDueNotifications
        );
    }
}
