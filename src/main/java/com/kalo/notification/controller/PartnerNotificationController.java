package com.kalo.notification.controller;

import com.kalo.notification.dto.CompanyNotificationResponse;
import com.kalo.notification.service.CompanyNotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Under /api/v1/partner/**, which SecurityConfig already restricts to PARTNER.
 * Neither list endpoint takes a company id: the company is resolved from the
 * token, so one partner cannot read another's notifications.
 */
@Tag(name = "Partner")
@RestController
@RequestMapping("/api/v1/partner/notifications")
@RequiredArgsConstructor
public class PartnerNotificationController {

    private final CompanyNotificationService notificationService;

    @GetMapping
    public ResponseEntity<Page<CompanyNotificationResponse>>
    getNotifications(
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                notificationService.getMyNotifications(pageable)
        );
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount() {

        return ResponseEntity.ok(
                Map.of("unread", notificationService.unreadCount())
        );
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<CompanyNotificationResponse>
    markRead(
            @PathVariable Long notificationId
    ) {

        return ResponseEntity.ok(
                notificationService.markRead(notificationId)
        );
    }
}
