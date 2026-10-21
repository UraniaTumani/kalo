package com.kalo.notification.service;

import com.kalo.notification.repository.CompanyNotificationRepository;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses only the disposable PostgreSQL Testcontainers database.
 * Verifies the real Liquibase unique index and real PostgreSQL ON CONFLICT.
 */
class DocumentExpiryNotificationSweepIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DocumentExpiryNotificationSweepService service;

    @Autowired
    CompanyNotificationRepository notifications;

    @Test
    void generatesOneAlertForEachStageAndDoesNotDuplicateOnRetry() {
        var company = fixtures.bookableCompany().company();
        long companyId = company.getId();
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Tirane"));

        setCompanyExpiry(companyId, today.plusDays(24));
        service.createDueNotifications(today);
        service.createDueNotifications(today);
        assertThat(keys(companyId)).containsExactly(
                "company-license:" + companyId + ":" + today.plusDays(24) + ":D30"
        );

        setCompanyExpiry(companyId, today.plusDays(5));
        service.createDueNotifications(today);
        service.createDueNotifications(today);
        assertThat(keys(companyId)).contains(
                "company-license:" + companyId + ":" + today.plusDays(5) + ":D7"
        );

        setCompanyExpiry(companyId, today.minusDays(1));
        service.createDueNotifications(today);
        service.createDueNotifications(today);
        assertThat(keys(companyId)).contains(
                "company-license:" + companyId + ":" + today.minusDays(1) + ":EXPIRED"
        );
        assertThat(keys(companyId)).hasSize(3);
    }

    @Test
    void neverNotifiesAnotherCompanyAboutTheFirstCompanysLicence() {
        var first = fixtures.bookableCompany().company();
        var other = fixtures.bookableCompany().company();
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Tirane"));

        setCompanyExpiry(first.getId(), today.plusDays(2));
        setCompanyExpiry(other.getId(), today.plusDays(90));

        service.createDueNotifications(today);
        assertThat(keys(first.getId())).hasSize(1);
        assertThat(keys(other.getId())).isEmpty();
    }


    @Test
    void unchangedExpiryProgressesThroughThirtySevenAndExpiredStagesOnceEach() {
        var company = fixtures.bookableCompany().company();
        long companyId = company.getId();
        LocalDate due = LocalDate.of(2026, 12, 15);
        setCompanyExpiry(companyId, due);

        service.createDueNotifications(due.minusDays(31));
        assertThat(keys(companyId)).isEmpty();

        service.createDueNotifications(due.minusDays(30));
        service.createDueNotifications(due.minusDays(30));
        assertThat(keys(companyId)).containsExactly(
                "company-license:" + companyId + ":" + due + ":D30"
        );

        service.createDueNotifications(due.minusDays(7));
        service.createDueNotifications(due.minusDays(1));
        service.createDueNotifications(due);
        assertThat(keys(companyId)).hasSize(2)
                .contains(
                        "company-license:" + companyId + ":" + due + ":D30",
                        "company-license:" + companyId + ":" + due + ":D7"
                );

        service.createDueNotifications(due.plusDays(1));
        service.createDueNotifications(due.plusDays(60));
        assertThat(keys(companyId)).hasSize(3)
                .contains(
                        "company-license:" + companyId + ":" + due + ":EXPIRED"
                );
    }

    private void setCompanyExpiry(Long companyId, LocalDate date) {
        jdbcTemplate.update(
                "UPDATE taxi_companies SET license_expiry_date = ? WHERE id = ?",
                java.sql.Date.valueOf(date),
                companyId
        );
    }

    private java.util.List<String> keys(Long companyId) {
        return notifications.findByCompanyId(companyId, Pageable.unpaged()).getContent()
                .stream()
                .map(n -> n.getNotificationKey())
                .filter(key -> key != null && key.startsWith("company-license:"))
                .toList();
    }
}
