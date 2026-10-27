package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.util.LicenseValidity;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class DriverSelfAvailabilityService {
    private final DriverSelfService self;
    private final JdbcTemplate jdbc;

    @Transactional
    public DriverAvailabilityStatus update(DriverAvailabilityStatus requested) {
        if (requested != DriverAvailabilityStatus.ONLINE && requested != DriverAvailabilityStatus.OFFLINE)
            throw new InvalidOperationException("Only ONLINE or OFFLINE can be selected");
        var candidate = self.current();
        var phone = SecurityContextHolder.getContext().getAuthentication().getName();
        var users = jdbc.queryForList("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        if (users.size() != 1) throw denied();
        var userId = users.get(0);
        lock("SELECT id FROM taxi_companies WHERE id = ? FOR UPDATE", candidate.companyId());
        lock("SELECT id FROM drivers WHERE id = ? FOR UPDATE", candidate.driverId());
        lock("SELECT id FROM users WHERE id = ? FOR UPDATE", userId);
        var eligible = jdbc.queryForObject("""
            SELECT count(*) FROM drivers d JOIN users u ON u.id = d.user_id
            JOIN taxi_companies c ON c.id = d.company_id
            WHERE d.id = ? AND c.id = ? AND u.id = ? AND u.phone = ?
              AND d.phone = u.phone AND u.role = 'DRIVER' AND u.status = 'ACTIVE'
              AND u.phone_verified = true AND d.status = 'ACTIVE'
              AND c.status = 'ACTIVE' AND c.verification_status = 'APPROVED'
            """, Long.class, candidate.driverId(), candidate.companyId(), userId, phone);
        if (eligible == null || eligible != 1L) throw denied();
        String current = jdbc.queryForObject("SELECT availability_status FROM drivers WHERE id = ?", String.class, candidate.driverId());
        if ("BUSY".equals(current)) throw new InvalidOperationException("Availability cannot be changed while the driver is busy");
        if (requested == DriverAvailabilityStatus.ONLINE) {
            LocalDate expiry = jdbc.queryForObject("SELECT license_expiry_date FROM drivers WHERE id = ?", LocalDate.class, candidate.driverId());
            if (!LicenseValidity.isValid(expiry)) throw new InvalidOperationException("Driver license is expired");
            Long assignments = jdbc.queryForObject("SELECT count(*) FROM driver_vehicle_assignments WHERE driver_id = ? AND active = true", Long.class, candidate.driverId());
            if (assignments == null || assignments != 1L) throw new InvalidOperationException("An active vehicle assignment is required");
        }
        jdbc.update("UPDATE drivers SET availability_status = ?, updated_at = ? WHERE id = ?", requested.name(), java.sql.Timestamp.from(java.time.Instant.now()), candidate.driverId());
        return requested;
    }
    private void lock(String sql, Long id) {
        if (jdbc.queryForList(sql, Long.class, id).size() != 1) throw denied();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Driver account unavailable"); }
}
