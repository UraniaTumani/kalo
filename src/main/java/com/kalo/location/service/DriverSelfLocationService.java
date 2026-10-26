package com.kalo.location.service;

import com.kalo.driver.service.DriverSelfService;
import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.location.dto.UpdateDriverLocationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DriverSelfLocationService {
    private final DriverSelfService self;
    private final DriverLocationWriter writer;
    private final JdbcTemplate jdbc;

    @Transactional
    public DriverLocationResponse update(UpdateDriverLocationRequest request) {
        // Initial lookup finds candidate IDs; it is not the final authorization decision.
        var candidate = self.current();
        String phone = SecurityContextHolder.getContext().getAuthentication().getName();
        var users = jdbc.queryForList("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        if (users.size() != 1) throw denied();
        Long userId = users.get(0);

        // Global order: company, driver, user. Each row is held until this transaction commits.
        lock("SELECT id FROM taxi_companies WHERE id = ? FOR UPDATE", candidate.companyId());
        lock("SELECT id FROM drivers WHERE id = ? FOR UPDATE", candidate.driverId());
        lock("SELECT id FROM users WHERE id = ? FOR UPDATE", userId);

        // A new JDBC read is essential: JPA may hold the pre-lock entity in its first-level cache.
        Long eligible = jdbc.queryForObject("""
            SELECT count(*) FROM drivers d JOIN users u ON u.id = d.user_id
            JOIN taxi_companies c ON c.id = d.company_id
            WHERE d.id = ? AND c.id = ? AND u.id = ? AND u.phone = ?
              AND d.phone = u.phone AND u.role = 'DRIVER' AND u.status = 'ACTIVE'
              AND u.phone_verified = true AND d.status = 'ACTIVE'
              AND c.status = 'ACTIVE' AND c.verification_status = 'APPROVED'
            """, Long.class, candidate.driverId(), candidate.companyId(), userId, phone);
        if (eligible == null || eligible != 1L) throw denied();
        return writer.write(candidate.driverId(), request);
    }

    private void lock(String sql, Long id) {
        if (jdbc.queryForList(sql, Long.class, id).size() != 1) throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Driver account unavailable");
    }
}
