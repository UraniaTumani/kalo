package com.kalo.ride.repository;

import com.kalo.ride.entity.RideOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RideOfferRepository
        extends JpaRepository<RideOffer, Long> {

    List<RideOffer> findAllByRideRequestId(
            Long rideRequestId
    );

    Optional<RideOffer>
    findByIdAndRideRequestIdAndRideRequestCustomerId(
            Long offerId,
            Long rideRequestId,
            Long customerId
    );
}