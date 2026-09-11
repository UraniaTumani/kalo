package com.kalo.partner.service;

import com.kalo.partner.entity.CompanyOperatingHours;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.CompanyOperatingHoursRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CompanyAvailabilityChecker {

    private static final ZoneId DEFAULT_ZONE =
            ZoneId.of("Europe/Tirane");

    private final CompanyOperatingHoursRepository
            companyOperatingHoursRepository;

    public boolean isAvailable(
            TaxiCompany company,
            Double pickupLatitude,
            Double pickupLongitude,
            Instant now
    ) {

        if (company == null) {
            return false;
        }

        /*
         * Company must accept bookings.
         */
        if (!company.isBookingEnabled()) {
            return false;
        }

        /*
         * Company must expose at least one
         * payment method.
         */
        if (company.getPaymentMethods() == null
                || company.getPaymentMethods().isEmpty()) {

            return false;
        }

        /*
         * Company must be open according
         * to today's operating hours.
         */
        if (!isInsideOperatingHours(
                company,
                now
        )) {

            return false;
        }

        /*
         * Service-area validation will be added
         * when TaxiCompany has service-area fields
         * such as center latitude/longitude + radius.
         *
         * For now the coordinates are accepted
         * but do not reject the company.
         */
        return true;
    }

    private boolean isInsideOperatingHours(
            TaxiCompany company,
            Instant now
    ) {

        LocalDateTime currentDateTime =
                LocalDateTime.ofInstant(
                        now,
                        DEFAULT_ZONE
                );

        DayOfWeek currentDay =
                currentDateTime.getDayOfWeek();

        LocalTime currentTime =
                currentDateTime.toLocalTime();

        Optional<CompanyOperatingHours> operatingHoursOptional =
                companyOperatingHoursRepository
                        .findByCompanyIdAndDayOfWeek(
                                company.getId(),
                                currentDay
                        );

        /*
         * If there is no configuration for today,
         * the company is considered unavailable.
         */
        if (operatingHoursOptional.isEmpty()) {
            return false;
        }

        CompanyOperatingHours operatingHours =
                operatingHoursOptional.get();

        /*
         * Day explicitly marked as closed.
         */
        if (operatingHours.isClosed()) {
            return false;
        }

        LocalTime openTime =
                operatingHours.getOpenTime();

        LocalTime closeTime =
                operatingHours.getCloseTime();

        /*
         * Open day must have both times.
         */
        if (openTime == null
                || closeTime == null) {

            return false;
        }

        /*
         * Normal operating hours.
         *
         * Example:
         * 08:00 -> 22:00
         */
        if (openTime.isBefore(closeTime)) {

            return !currentTime.isBefore(openTime)
                    && currentTime.isBefore(closeTime);
        }

        /*
         * Overnight operating hours.
         *
         * Example:
         * 20:00 -> 04:00
         */
        if (openTime.isAfter(closeTime)) {

            return !currentTime.isBefore(openTime)
                    || currentTime.isBefore(closeTime);
        }

        /*
         * openTime == closeTime
         *
         * We treat this as open 24 hours
         * for the configured day.
         */
        return true;
    }
}