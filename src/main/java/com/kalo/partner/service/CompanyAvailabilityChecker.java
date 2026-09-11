package com.kalo.partner.service;

import com.kalo.common.util.DistanceCalculator;
import com.kalo.partner.entity.CompanyOperatingHours;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.CompanyOperatingHoursRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyAvailabilityChecker {

    private static final ZoneId FALLBACK_ZONE =
            ZoneId.of("Europe/Tirane");

    private final CompanyOperatingHoursRepository
            companyOperatingHoursRepository;

    /**
     * Single-company check. Loads the company's operating hours itself, so it
     * costs one query; callers evaluating many companies should preload the
     * rows and use {@link #isAvailable(TaxiCompany, Collection, Double, Double, Instant)}.
     */
    public boolean isAvailable(
            TaxiCompany company,
            Double pickupLatitude,
            Double pickupLongitude,
            Instant now
    ) {

        if (company == null) {
            return false;
        }

        List<CompanyOperatingHours> operatingHours =
                companyOperatingHoursRepository
                        .findAllByCompanyIdOrderByDayOfWeek(
                                company.getId()
                        );

        return isAvailable(
                company,
                operatingHours,
                pickupLatitude,
                pickupLongitude,
                now
        );
    }

    /**
     * Same rules, evaluated against already-loaded operating hours.
     *
     * @param operatingHours every configured row for this company; rows for
     *                       other companies are ignored
     */
    public boolean isAvailable(
            TaxiCompany company,
            Collection<CompanyOperatingHours> operatingHours,
            Double pickupLatitude,
            Double pickupLongitude,
            Instant now
    ) {

        if (company == null) {
            return false;
        }

        if (!company.isBookingEnabled()) {
            return false;
        }

        if (company.getPaymentMethods() == null
                || company.getPaymentMethods().isEmpty()) {

            return false;
        }

        if (!isInsideServiceArea(
                company,
                pickupLatitude,
                pickupLongitude
        )) {

            return false;
        }

        return isInsideOperatingHours(
                company,
                operatingHours,
                now
        );
    }

    /**
     * A company that has not configured a service area serves everywhere the
     * search radius reaches; the search radius itself is the outer bound.
     */
    private boolean isInsideServiceArea(
            TaxiCompany company,
            Double pickupLatitude,
            Double pickupLongitude
    ) {

        Double centerLatitude =
                company.getServiceCenterLatitude();

        Double centerLongitude =
                company.getServiceCenterLongitude();

        Double radiusKm =
                company.getServiceRadiusKm();

        if (centerLatitude == null
                || centerLongitude == null
                || radiusKm == null) {

            return true;
        }

        if (pickupLatitude == null
                || pickupLongitude == null) {

            return false;
        }

        double distanceKm =
                DistanceCalculator.calculateDistanceKm(
                        centerLatitude,
                        centerLongitude,
                        pickupLatitude,
                        pickupLongitude
                );

        return distanceKm <= radiusKm;
    }

    private boolean isInsideOperatingHours(
            TaxiCompany company,
            Collection<CompanyOperatingHours> operatingHours,
            Instant now
    ) {

        if (operatingHours == null
                || operatingHours.isEmpty()) {

            return false;
        }

        LocalDateTime currentDateTime =
                LocalDateTime.ofInstant(
                        now,
                        resolveZone(company)
                );

        DayOfWeek currentDay =
                currentDateTime.getDayOfWeek();

        LocalTime currentTime =
                currentDateTime.toLocalTime();

        CompanyOperatingHours today =
                operatingHours
                        .stream()
                        .filter(hours ->
                                hours.getDayOfWeek() == currentDay
                        )
                        .findFirst()
                        .orElse(null);

        /*
         * No configuration for today means the company is not open today.
         */
        if (today == null) {
            return false;
        }

        if (today.isClosed()) {
            return false;
        }

        LocalTime openTime =
                today.getOpenTime();

        LocalTime closeTime =
                today.getCloseTime();

        if (openTime == null
                || closeTime == null) {

            return false;
        }

        /*
         * Normal hours, e.g. 08:00 -> 22:00
         */
        if (openTime.isBefore(closeTime)) {

            return !currentTime.isBefore(openTime)
                    && currentTime.isBefore(closeTime);
        }

        /*
         * Overnight hours, e.g. 20:00 -> 04:00
         */
        if (openTime.isAfter(closeTime)) {

            return !currentTime.isBefore(openTime)
                    || currentTime.isBefore(closeTime);
        }

        /*
         * openTime == closeTime is read as open all day.
         */
        return true;
    }

    private ZoneId resolveZone(
            TaxiCompany company
    ) {

        String timezone =
                company.getTimezone();

        if (timezone == null
                || timezone.isBlank()) {

            return FALLBACK_ZONE;
        }

        try {

            return ZoneId.of(timezone);

        } catch (DateTimeException exception) {

            log.warn(
                    "Company {} has an unusable timezone '{}', falling back to {}",
                    company.getId(),
                    timezone,
                    FALLBACK_ZONE
            );

            return FALLBACK_ZONE;
        }
    }
}
