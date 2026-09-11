package com.kalo.ride.service;

import com.kalo.ride.dto.AcceptRideRequest;
import com.kalo.ride.dto.CompleteRideRequest;
import com.kalo.ride.dto.PartnerRideResponse;
import com.kalo.ride.dto.RideResponse;
import com.kalo.ride.dto.SelectTaxiOfferRequest;
import com.kalo.ride.enums.RideStatus;

import java.util.List;

public interface RideService {

    RideResponse selectTaxiOffer(
            Long rideRequestId,
            SelectTaxiOfferRequest request
    );

    List<PartnerRideResponse> getPartnerRides(
            RideStatus status
    );

    RideResponse acceptRide(
            Long rideId,
            AcceptRideRequest request
    );

    RideResponse declineRide(
            Long rideId
    );

    RideResponse markDriverArriving(
            Long rideId
    );

    RideResponse markDriverArrived(
            Long rideId
    );

    RideResponse startRide(
            Long rideId
    );

    RideResponse completeRide(
            Long rideId,
            CompleteRideRequest request
    );

    RideResponse getCurrentRide();

    List<RideResponse> getRideHistory();

    RideResponse getRideById(
            Long rideId
    );

    RideResponse cancelRide(
            Long rideId
    );
}