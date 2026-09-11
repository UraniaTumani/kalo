package com.kalo.ride.service;

import com.kalo.ride.dto.GuestAvailabilityRequest;
import com.kalo.ride.dto.GuestAvailabilityResponse;
import com.kalo.ride.dto.GuestTaxiOptionResponse;
import com.kalo.ride.service.TaxiAvailabilityFinder.AvailableTaxi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Lets a visitor without an account see which taxi companies could serve a
 * location right now.
 *
 * Read-only by design: unlike the customer search this creates no RideRequest
 * and no RideOffer. An anonymous caller therefore cannot fill the tables with
 * rows that can never become rides, and every existing invariant — one active
 * ride per customer, one company per request — is untouched. Booking still
 * requires an account, because a Ride needs a real customer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestAvailabilityService {

    private static final String PRICING_NOTE =
            "Final price by taximeter";

    private final TaxiAvailabilityFinder taxiAvailabilityFinder;

    @Transactional(readOnly = true)
    public GuestAvailabilityResponse checkAvailability(
            GuestAvailabilityRequest request
    ) {

        Instant now = Instant.now();

        List<AvailableTaxi> candidates =
                taxiAvailabilityFinder.findNearestPerCompany(
                        request.latitude(),
                        request.longitude(),
                        now
                );

        List<GuestTaxiOptionResponse> options =
                candidates
                        .stream()
                        .map(this::mapToResponse)
                        .toList();

        log.info(
                "Guest availability check returned {} companies",
                options.size()
        );

        return new GuestAvailabilityResponse(
                now,
                options.size(),
                options,
                "Sign up to book. You choose the company, they assign the driver, "
                        + "and you pay the driver directly by taximeter."
        );
    }

    private GuestTaxiOptionResponse mapToResponse(
            AvailableTaxi candidate
    ) {

        return new GuestTaxiOptionResponse(
                candidate.company().getId(),
                candidate.company().getDisplayName(),
                candidate.company().getRating(),
                candidate.company().getRatingCount(),
                roundDistance(candidate.distanceKm()),
                candidate.vehicle().getVehicleType(),
                Set.copyOf(candidate.company().getPaymentMethods()),
                PRICING_NOTE
        );
    }

    private double roundDistance(double distance) {
        return Math.round(distance * 100.0) / 100.0;
    }
}
