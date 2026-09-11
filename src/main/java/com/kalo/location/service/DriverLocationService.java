package com.kalo.location.service;

import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.location.dto.UpdateDriverLocationRequest;

public interface DriverLocationService {

    DriverLocationResponse updateLocation(
            Long driverId,
            UpdateDriverLocationRequest request
    );

    DriverLocationResponse getLocation(
            Long driverId
    );
}