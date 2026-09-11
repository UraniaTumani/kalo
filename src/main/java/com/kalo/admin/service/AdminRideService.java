package com.kalo.admin.service;

import com.kalo.admin.dto.AdminRideDetailResponse;
import com.kalo.admin.dto.AdminRideResponse;
import com.kalo.ride.enums.RideStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminRideService {

    Page<AdminRideResponse> getRides(
            RideStatus status,
            Long companyId,
            Pageable pageable
    );

    AdminRideDetailResponse getRideById(
            Long rideId
    );
}
