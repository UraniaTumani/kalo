package com.kalo.ride.controller;

import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.service.DriverRideActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DriverRideActionController {
    private final DriverRideActionService actions;
    @PostMapping("/api/v1/driver/me/rides/{rideId}/driver-arriving")
    public ResponseEntity<DriverRideActionService.Result> arriving(@PathVariable Long rideId) {
        return result(rideId, RideStatus.DRIVER_ARRIVING);
    }
    @PostMapping("/api/v1/driver/me/rides/{rideId}/driver-arrived")
    public ResponseEntity<DriverRideActionService.Result> arrived(@PathVariable Long rideId) {
        return result(rideId, RideStatus.DRIVER_ARRIVED);
    }
    @PostMapping("/api/v1/driver/me/rides/{rideId}/start")
    public ResponseEntity<DriverRideActionService.Result> start(@PathVariable Long rideId) {
        return result(rideId, RideStatus.IN_PROGRESS);
    }
    private ResponseEntity<DriverRideActionService.Result> result(Long id, RideStatus target) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(actions.advance(id, target));
    }
}
