package com.kalo.driver.service;

import com.kalo.driver.dto.CreateDriverRequest;
import com.kalo.driver.dto.DriverResponse;
import com.kalo.driver.dto.UpdateDriverAvailabilityRequest;
import com.kalo.driver.dto.UpdateDriverRequest;

import java.util.List;

public interface DriverService {

    DriverResponse createDriver(
            CreateDriverRequest request
    );

    List<DriverResponse> getDrivers();

    DriverResponse getDriverById(
            Long driverId
    );

    DriverResponse updateDriver(
            Long driverId,
            UpdateDriverRequest request
    );

    DriverResponse updateAvailability(
            Long driverId,
            UpdateDriverAvailabilityRequest request
    );

    void deleteDriver(
            Long driverId
    );
}