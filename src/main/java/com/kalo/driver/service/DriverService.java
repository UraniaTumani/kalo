package com.kalo.driver.service;

import com.kalo.driver.dto.CreateDriverRequest;
import com.kalo.driver.dto.DriverResponse;
import com.kalo.driver.dto.UpdateDriverAvailabilityRequest;
import com.kalo.driver.dto.UpdateDriverRequest;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface DriverService {

    DriverResponse createDriver(
            CreateDriverRequest request
    );

    Page<DriverResponse> getDrivers(
            DriverStatus status,
            DriverAvailabilityStatus availabilityStatus,
            Boolean unassigned,
            Pageable pageable
    );

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