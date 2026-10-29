package com.kalo.ride.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.enums.RideStatus;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Called with a locked ride, inside the caller's transaction. */
@Component
public class RideProgression {
    public void advance(Ride ride, RideStatus target) {
        RideStatus expected = switch (target) {
            case DRIVER_ARRIVING -> RideStatus.DRIVER_ASSIGNED;
            case DRIVER_ARRIVED -> RideStatus.DRIVER_ARRIVING;
            case IN_PROGRESS -> RideStatus.DRIVER_ARRIVED;
            default -> throw new InvalidOperationException("Unsupported ride transition");
        };
        if (ride.getStatus() != expected) throw new InvalidOperationException(switch (target) {
            case DRIVER_ARRIVING -> "Ride must be driver assigned before driver can start arriving";
            case DRIVER_ARRIVED -> "Ride must be driver arriving before marking driver as arrived";
            case IN_PROGRESS -> "Ride can only start after driver has arrived";
            default -> "Unsupported ride transition";
        });
        ride.setStatus(target);
        var now = Instant.now();
        switch (target) {
            case DRIVER_ARRIVING -> ride.setDriverArrivingAt(now);
            case DRIVER_ARRIVED -> ride.setDriverArrivedAt(now);
            case IN_PROGRESS -> ride.setStartedAt(now);
            default -> throw new InvalidOperationException("Unsupported ride transition");
        }
    }
}
