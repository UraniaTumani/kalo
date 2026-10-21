package com.kalo.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * F26.2: calculates stored expiry notifications. Deliberately separate from
 * partner requests: reading the dashboard never creates notification rows.
 *
 * Idempotency is enforced by the database unique index, not by an
 * exists-then-insert race. Expiry dates are compared in Europe/Tirane.
 */
@Service
@RequiredArgsConstructor
public class DocumentExpiryNotificationSweepService {

    private static final ZoneId TIRANA = ZoneId.of("Europe/Tirane");

    private final JdbcTemplate jdbcTemplate;

    /**
     * Each row is one dated fact belonging to exactly one existing company.
     * Document owner IDs are polymorphic; the explicit owner-specific JOINs
     * prevent accidentally sending one partner another partner's alert.
     */
    private static final String SOURCES = """
            SELECT c.id AS company_id, 'company-license:' || c.id AS source_key,
                   'Company taxi licence' AS label, c.license_expiry_date AS expires_at
              FROM taxi_companies c
             WHERE c.license_expiry_date IS NOT NULL
            UNION ALL
            SELECT d.company_id, 'driver-license:' || d.id,
                   'Driver licence: ' || d.first_name || ' ' || d.last_name, d.license_expiry_date
              FROM drivers d
             WHERE d.license_expiry_date IS NOT NULL
            UNION ALL
            SELECT v.company_id, 'vehicle-registration:' || v.id,
                   'Vehicle registration: ' || v.plate_number, v.registration_expiry_date
              FROM vehicles v
             WHERE v.registration_expiry_date IS NOT NULL
            UNION ALL
            SELECT v.company_id, 'vehicle-insurance:' || v.id,
                   'Vehicle insurance: ' || v.plate_number, v.insurance_expiry_date
              FROM vehicles v
             WHERE v.insurance_expiry_date IS NOT NULL
            UNION ALL
            SELECT v.company_id, 'vehicle-inspection:' || v.id,
                   'Vehicle inspection: ' || v.plate_number, v.technical_inspection_expiry_date
              FROM vehicles v
             WHERE v.technical_inspection_expiry_date IS NOT NULL
            UNION ALL
            SELECT c.id, 'document:' || doc.id, 'Document ' || doc.document_type,
                   doc.expires_at
              FROM documents doc
              JOIN taxi_companies c ON doc.owner_type = 'COMPANY' AND doc.owner_id = c.id
             WHERE doc.expires_at IS NOT NULL
            UNION ALL
            SELECT drv.company_id, 'document:' || doc.id, 'Driver document ' || doc.document_type ||
                   ': ' || drv.first_name || ' ' || drv.last_name, doc.expires_at
              FROM documents doc
              JOIN drivers drv ON doc.owner_type = 'DRIVER' AND doc.owner_id = drv.id
             WHERE doc.expires_at IS NOT NULL
            UNION ALL
            SELECT v.company_id, 'document:' || doc.id, 'Vehicle document ' || doc.document_type ||
                   ': ' || v.plate_number, doc.expires_at
              FROM documents doc
              JOIN vehicles v ON doc.owner_type = 'VEHICLE' AND doc.owner_id = v.id
             WHERE doc.expires_at IS NOT NULL
            """;

    private static final String SELECT_DUE = """
            SELECT company_id, source_key, label, expires_at
              FROM (
            """ + SOURCES + """
              ) exp
             WHERE expires_at <= ?
            """;

    private static final String INSERT = """
            INSERT INTO company_notifications
                (company_id, type, ride_id, message, read_at, notification_key, created_at, updated_at)
            VALUES (?, 'DOCUMENT_EXPIRY', NULL, ?, NULL, ?, NOW(), NOW())
            ON CONFLICT (notification_key) DO NOTHING
            """;

    @Transactional
    public int createDueNotifications() {
        return createDueNotifications(LocalDate.now(TIRANA));
    }

    // Package-private so unit and integration tests can choose a fixed date.
    @Transactional
    int createDueNotifications(LocalDate today) {
        List<ExpiryFact> facts = jdbcTemplate.query(
                SELECT_DUE,
                (rs, rowNum) -> new ExpiryFact(
                        rs.getLong("company_id"),
                        rs.getString("source_key"),
                        rs.getString("label"),
                        rs.getDate("expires_at").toLocalDate()
                ),
                Date.valueOf(today.plusDays(30))
        );

        int inserted = 0;
        for (ExpiryFact fact : facts) {
            // Use epoch-day arithmetic, not Period.getDays(), which wraps at months.
            long remaining = fact.expiresAt().toEpochDay() - today.toEpochDay();
            String tier = remaining < 0 ? "EXPIRED" : remaining <= 7 ? "D7" : "D30";
            String text = switch (tier) {
                case "EXPIRED" -> fact.label() + " expired on " + fact.expiresAt() + ".";
                case "D7" -> fact.label() + " expires soon (within 7 days): " + fact.expiresAt() + ".";
                default -> fact.label() + " expires within 30 days: " + fact.expiresAt() + ".";
            };
            String key = fact.sourceKey() + ":" + fact.expiresAt() + ":" + tier;
            if (key.length() > 180) {
                throw new IllegalStateException("Document expiry notification key too long");
            }
            inserted += jdbcTemplate.update(INSERT, fact.companyId(), text, key);
        }
        return inserted;
    }

    private record ExpiryFact(long companyId, String sourceKey, String label, LocalDate expiresAt) {}
}
