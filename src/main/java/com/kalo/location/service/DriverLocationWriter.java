package com.kalo.location.service;

import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.location.dto.UpdateDriverLocationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One atomic database write shared by both partner and driver APIs. */
@Component
@RequiredArgsConstructor
public class DriverLocationWriter {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public DriverLocationResponse write(Long driverId, UpdateDriverLocationRequest request) {
        return jdbc.queryForObject("""
            INSERT INTO driver_locations (driver_id, latitude, longitude, location_updated_at, created_at, updated_at)
            VALUES (?, ?, ?, clock_timestamp(), clock_timestamp(), clock_timestamp())
            ON CONFLICT (driver_id) DO UPDATE SET latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude, location_updated_at = clock_timestamp(), updated_at = clock_timestamp()
            RETURNING driver_id, latitude, longitude, location_updated_at
            """, (rs, row) -> new DriverLocationResponse(rs.getLong(1), rs.getDouble(2),
                rs.getDouble(3), rs.getTimestamp(4).toInstant()),
                driverId, request.latitude(), request.longitude());
    }
}
