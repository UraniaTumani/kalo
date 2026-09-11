package com.kalo.ride.service;

import com.kalo.partner.service.CompanyAvailabilityChecker;
import com.kalo.ride.dto.CreateRideRequest;
import com.kalo.ride.dto.RideSearchResponse;
import com.kalo.partner.service.CompanyAvailabilityChecker;

public interface RideSearchService {

    RideSearchResponse searchTaxis(
            CreateRideRequest request
    );



}