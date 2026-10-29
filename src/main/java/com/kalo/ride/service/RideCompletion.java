package com.kalo.ride.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.enums.RideRequestStatus;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;

/** Both callers hold ride and driver locks, in a single transaction. */
@Component
public class RideCompletion {
    public void complete(Ride ride, Driver driver, BigDecimal amount) {
        if (ride.getStatus() != RideStatus.IN_PROGRESS)
            throw new InvalidOperationException("Only a ride in progress can be completed");
        if (driver == null || ride.getDriver() == null || !driver.getId().equals(ride.getDriver().getId()))
            throw new InvalidOperationException("Ride does not have an assigned driver");
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw new InvalidOperationException("Final amount must be positive with at most 2 decimal places");
        ride.setFinalAmount(amount);
        ride.setCompletedAt(Instant.now());
        ride.setStatus(RideStatus.COMPLETED);
        ride.getRideRequest().setStatus(RideRequestStatus.SELECTED);
        driver.setAvailabilityStatus(DriverAvailabilityStatus.ONLINE);
    }
}
