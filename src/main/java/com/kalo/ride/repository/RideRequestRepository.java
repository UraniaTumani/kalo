package com.kalo.ride.repository;

import com.kalo.ride.entity.RideRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RideRequestRepository
        extends JpaRepository<RideRequest, Long> {

    Optional<RideRequest> findByIdAndCustomerId(
            Long rideRequestId,
            Long customerId
    );
}