package com.kalo.ride.service;

import com.kalo.driver.service.DriverSelfService;
import com.kalo.ride.dto.DriverCurrentRideResponse;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.DriverCurrentRideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DriverCurrentRideService {
    private final DriverSelfService self;
    private final DriverCurrentRideRepository rides;

    @Transactional(readOnly = true)
    public Optional<DriverCurrentRideResponse> current() {
        var driver = self.current();
        return rides.findCurrent(driver.driverId(), driver.companyId(), driver.phone(), RideStatus.ACTIVE_STATUSES)
                .map(ride -> {
                    var request = ride.getRideRequest();
                    return new DriverCurrentRideResponse(ride.getId(), ride.getStatus(),
                            ride.getVehicle() == null ? null : ride.getVehicle().getId(),
                            request.getPickupAddress(), request.getPickupLatitude(), request.getPickupLongitude(),
                            request.getDestinationAddress(), request.getDestinationLatitude(), request.getDestinationLongitude(),
                            ride.getAcceptedAt());
                });
    }
}
