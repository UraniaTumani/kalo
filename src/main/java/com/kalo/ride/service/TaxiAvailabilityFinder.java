package com.kalo.ride.service;

import com.kalo.common.util.DistanceCalculator;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.CompanyOperatingHours;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.CompanyOperatingHoursRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.partner.service.CompanyAvailabilityChecker;
import com.kalo.ride.repository.AvailableTaxiRow;
import com.kalo.ride.repository.TaxiSearchRepository;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Works out which taxi companies can serve a pickup point right now.
 *
 * Owned separately from the ride flow because two callers need exactly the
 * same rules: the customer search, which turns the result into persisted
 * RideOffers, and the public availability endpoint, which persists nothing.
 * Keeping one implementation means a guest can never be shown a company that
 * a signed-in customer would not be offered.
 */
@Service
@RequiredArgsConstructor
public class TaxiAvailabilityFinder {

    /** A position older than this is treated as unknown. */
    public static final Duration MAX_LOCATION_AGE =
            Duration.ofMinutes(2);

    public static final double MAX_SEARCH_RADIUS_KM = 10.0;

    private final TaxiSearchRepository taxiSearchRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final CompanyOperatingHoursRepository operatingHoursRepository;
    private final CompanyAvailabilityChecker companyAvailabilityChecker;

    /**
     * One entry per company: the nearest driver that company currently has
     * available, ordered nearest first.
     *
     * @param latitude  pickup latitude
     * @param longitude pickup longitude
     */
    @Transactional(readOnly = true)
    public List<AvailableTaxi> findNearestPerCompany(
            double latitude,
            double longitude,
            Instant now
    ) {

        /*
         * One query returns driver + active vehicle assignment + vehicle +
         * company + last position. Everything expressible in SQL (driver
         * ACTIVE/ONLINE, vehicle ACTIVE, company APPROVED/ACTIVE, booking
         * enabled, fresh GPS) is filtered by the database.
         */
        List<AvailableTaxiRow> rows =
                taxiSearchRepository
                        .findAvailableTaxis(
                                DriverStatus.ACTIVE,
                                DriverAvailabilityStatus.ONLINE,
                                VehicleStatus.ACTIVE,
                                VerificationStatus.APPROVED,
                                CompanyStatus.ACTIVE,
                                now.minus(MAX_LOCATION_AGE)
                        );

        /*
         * Straight-line distance, not a routed ETA.
         */
        List<AvailableTaxi> withinRadius =
                new ArrayList<>();

        for (AvailableTaxiRow row : rows) {

            double distanceKm =
                    DistanceCalculator.calculateDistanceKm(
                            latitude,
                            longitude,
                            row.latitude(),
                            row.longitude()
                    );

            if (distanceKm > MAX_SEARCH_RADIUS_KM) {
                continue;
            }

            withinRadius.add(
                    new AvailableTaxi(
                            row.company(),
                            row.driver(),
                            row.vehicle(),
                            distanceKm
                    )
            );
        }

        /*
         * Payment methods and operating hours are loaded for every surviving
         * company at once, then evaluated in memory, so the company-level
         * rules cost two queries rather than two per company.
         */
        Set<Long> companyIds =
                withinRadius
                        .stream()
                        .map(candidate -> candidate.company().getId())
                        .collect(Collectors.toSet());

        Map<Long, List<CompanyOperatingHours>> operatingHoursByCompany =
                loadOperatingHours(companyIds);

        if (!companyIds.isEmpty()) {
            /*
             * Initialises the lazy payment-method collections on the same
             * managed company instances the candidates already hold.
             */
            taxiCompanyRepository.findAllWithPaymentMethods(companyIds);
        }

        List<AvailableTaxi> available =
                new ArrayList<>();

        for (AvailableTaxi candidate : withinRadius) {

            TaxiCompany company = candidate.company();

            boolean open =
                    companyAvailabilityChecker.isAvailable(
                            company,
                            operatingHoursByCompany.getOrDefault(
                                    company.getId(),
                                    List.of()
                            ),
                            latitude,
                            longitude,
                            now
                    );

            if (open) {
                available.add(candidate);
            }
        }

        available.sort(
                Comparator.comparingDouble(AvailableTaxi::distanceKm)
        );

        /*
         * The customer picks a company, not a driver, so each company appears
         * once represented by its nearest available driver.
         */
        Map<Long, AvailableTaxi> nearestByCompany =
                new LinkedHashMap<>();

        for (AvailableTaxi candidate : available) {

            nearestByCompany.putIfAbsent(
                    candidate.company().getId(),
                    candidate
            );
        }

        return List.copyOf(nearestByCompany.values());
    }

    private Map<Long, List<CompanyOperatingHours>> loadOperatingHours(
            Set<Long> companyIds
    ) {

        if (companyIds.isEmpty()) {
            return Map.of();
        }

        return operatingHoursRepository
                .findAllByCompanyIdIn(companyIds)
                .stream()
                .collect(
                        Collectors.groupingBy(hours ->
                                hours.getCompany().getId()
                        )
                );
    }

    /**
     * A company that can serve the pickup point, with the nearest driver and
     * the vehicle they are currently assigned to.
     */
    public record AvailableTaxi(

            TaxiCompany company,

            Driver driver,

            Vehicle vehicle,

            double distanceKm

    ) {
    }
}
