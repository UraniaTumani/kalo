package com.kalo.rating.service;

import com.kalo.rating.dto.CreateRideRatingRequest;
import com.kalo.rating.dto.RideRatingResponse;

public interface RideRatingService {

    RideRatingResponse rateRide(
            Long rideId,
            CreateRideRatingRequest request
    );
}