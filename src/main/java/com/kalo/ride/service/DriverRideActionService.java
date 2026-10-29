package com.kalo.ride.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.service.DriverSelfService;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.DriverRideActionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DriverRideActionService {
    private final DriverSelfService self;
    private final DriverRideActionRepository rides;
    private final RideProgression progression;
    private final JdbcTemplate jdbc;
    private final RideCompletion completion;
    private final jakarta.persistence.EntityManager entities;
    public record Result(Long rideId, RideStatus status) {}

    @Transactional
    public Result advance(Long rideId, RideStatus target) {
        var ride = lockAuthorized(rideId);
        progression.advance(ride, target);
        return new Result(ride.getId(), ride.getStatus());
    }

    @Transactional
    public Result complete(Long rideId, com.kalo.ride.dto.CompleteRideRequest request) {
        var ride = lockAuthorized(rideId);
        var driver = ride.getDriver();
        // self.current() may have loaded this entity before waiting for locks.
        entities.refresh(driver);
        completion.complete(ride, driver, request.finalAmount());
        return new Result(ride.getId(), ride.getStatus());
    }

    private com.kalo.ride.entity.Ride lockAuthorized(Long rideId) {
        var driver = self.current();
        // Ride first, matching partner lifecycle/cancellation; never lock driver then ride.
        var ride = rides.lockOwned(rideId, driver.driverId(), driver.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found"));
        var users = jdbc.queryForList("SELECT id FROM users WHERE phone = ?", Long.class, driver.phone());
        if (users.size() != 1) throw denied();
        var userId = users.get(0);
        lock("SELECT id FROM taxi_companies WHERE id = ? FOR UPDATE", driver.companyId());
        lock("SELECT id FROM drivers WHERE id = ? FOR UPDATE", driver.driverId());
        lock("SELECT id FROM users WHERE id = ? FOR UPDATE", userId);
        Long eligible = jdbc.queryForObject("""
            SELECT count(*) FROM drivers d JOIN users u ON u.id=d.user_id JOIN taxi_companies c ON c.id=d.company_id
            WHERE d.id=? AND c.id=? AND u.id=? AND u.phone=? AND d.phone=u.phone
              AND u.role='DRIVER' AND u.status='ACTIVE' AND u.phone_verified=true
              AND d.status='ACTIVE' AND c.status='ACTIVE' AND c.verification_status='APPROVED'
            """, Long.class, driver.driverId(), driver.companyId(), userId, driver.phone());
        if (eligible == null || eligible != 1L) throw denied();
        return ride;
    }
    private void lock(String sql, Long id) {
        if (jdbc.queryForList(sql, Long.class, id).size() != 1) throw denied();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Driver account unavailable"); }
}
