package com.kalo.notification.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.notification.dto.CompanyNotificationResponse;
import com.kalo.notification.entity.CompanyNotification;
import com.kalo.notification.enums.NotificationType;
import com.kalo.notification.repository.CompanyNotificationRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyNotificationServiceImpl implements CompanyNotificationService {

    private final CompanyNotificationRepository notificationRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;

    @Override
    @Transactional
    public void rideRated(
            TaxiCompany company,
            Long rideId,
            int driverRating,
            int companyRating
    ) {

        CompanyNotification notification = new CompanyNotification();

        notification.setCompany(company);
        notification.setType(NotificationType.RIDE_RATED);
        notification.setRideId(rideId);

        /*
         * Rendered once, at write time, rather than assembled on every read.
         * A notification is a record of what was true when it happened; if the
         * ride is later corrected the message should still say what the
         * passenger actually submitted.
         */
        notification.setMessage(
                "A passenger rated ride #" + rideId
                        + ": driver " + driverRating + "/5, company "
                        + companyRating + "/5."
        );

        notificationRepository.save(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CompanyNotificationResponse> getMyNotifications(
            Pageable pageable
    ) {

        return notificationRepository
                .findByCompanyId(currentCompany().getId(), pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount() {

        return notificationRepository
                .countByCompanyIdAndReadAtIsNull(currentCompany().getId());
    }

    @Override
    @Transactional
    public CompanyNotificationResponse markRead(
            Long notificationId
    ) {

        TaxiCompany company = currentCompany();

        CompanyNotification notification =
                notificationRepository
                        .findById(notificationId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Notification not found"
                                )
                        );

        /*
         * Checked rather than trusted. findById is the only lookup here that
         * takes an id off the request, so it is the only place another
         * company's row could be reached.
         */
        if (!notification.getCompany().getId().equals(company.getId())) {
            throw new ResourceNotFoundException("Notification not found");
        }

        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }

        return mapToResponse(notification);
    }

    private TaxiCompany currentCompany() {

        String phone =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        return taxiCompanyRepository
                .findByOwnerPhone(phone)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Taxi company not found"
                        )
                );
    }

    private CompanyNotificationResponse mapToResponse(
            CompanyNotification notification
    ) {

        return new CompanyNotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getRideId(),
                notification.getMessage(),
                notification.getReadAt() != null,
                notification.getCreatedAt()
        );
    }
}
