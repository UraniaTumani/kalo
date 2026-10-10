package com.kalo.ride.service;

import com.kalo.ride.dto.AcceptRideRequest;
import com.kalo.ride.dto.CompleteRideRequest;
import com.kalo.ride.dto.PartnerRideResponse;
import com.kalo.ride.dto.RideResponse;
import com.kalo.ride.dto.SelectTaxiOfferRequest;
import com.kalo.ride.enums.RideStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RideService {

    RideResponse selectTaxiOffer(
            Long rideRequestId,
            SelectTaxiOfferRequest request
    );

    /**
     * The partner's ride queue.
     *
     * @param status one status to show, or null for no status filter
     * @param active TRUE to show everything still in flight
     *               ({@link RideStatus#ACTIVE_STATUSES}); null or FALSE applies
     *               no active filter, so {@code status} still governs
     * @throws com.kalo.common.exception.InvalidOperationException if both
     *         {@code active=true} and a {@code status} are given
     */
    Page<PartnerRideResponse> getPartnerRides(
            RideStatus status,
            Boolean active,
            Pageable pageable
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

    Page<RideResponse> getRideHistory(
            Pageable pageable
    );

    RideResponse getRideById(
            Long rideId
    );

    RideResponse cancelRide(
            Long rideId
    );
}