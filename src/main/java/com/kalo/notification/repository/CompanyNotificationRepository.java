package com.kalo.notification.repository;

import com.kalo.notification.entity.CompanyNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyNotificationRepository
        extends JpaRepository<CompanyNotification, Long> {

    /**
     * Scoped by company id at the query, like every other partner-owned list.
     * There is no findById for the partner path, so no id to guess at.
     */
    Page<CompanyNotification> findByCompanyId(
            Long companyId,
            Pageable pageable
    );

    long countByCompanyIdAndReadAtIsNull(
            Long companyId
    );
}
