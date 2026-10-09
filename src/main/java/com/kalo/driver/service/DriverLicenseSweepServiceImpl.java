package com.kalo.driver.service;

import com.kalo.common.util.LicenseValidity;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverLicenseSweepServiceImpl
        implements DriverLicenseSweepService {

    private final DriverRepository driverRepository;

    /**
     * Idempotent by construction rather than by a flag.
     *
     * The query selects on the state it is about to change — ONLINE with an
     * expired licence — so a second run finds nothing, and the run after a new
     * licence lapses finds only that driver. There is no "already swept" marker
     * to keep in step with reality.
     *
     * Each row is then re-read under a pessimistic lock and re-checked, which is
     * the same shape as the ride timeout sweep and exists for the same reason:
     * between the first query and the write, something else may have moved the
     * row. Two re-checks matter here.
     *
     * The availability re-check is the one that protects a ride. A driver who has
     * become BUSY since the first query is on a journey with a passenger in the
     * car, and writing OFFLINE over that would strand them mid-ride and remove
     * their company from search with no explanation. Accepting a ride already
     * refuses an expired licence, so this ought to be unreachable — which is
     * exactly why it is checked rather than assumed.
     *
     * The licence re-check covers the opposite case: a partner correcting the
     * expiry date while the sweep is in flight should not have their driver taken
     * offline on the strength of a value that is no longer there.
     */
    @Override
    @Transactional
    public int takeExpiredDriversOffline() {

        LocalDate today =
                LicenseValidity.today();

        List<Long> candidates =
                driverRepository
                        .findIdsOnlineWithLicenseExpiredBefore(
                                DriverAvailabilityStatus.ONLINE,
                                today
                        );

        int takenOffline = 0;

        for (Long driverId : candidates) {

            Driver driver =
                    driverRepository
                            .findForUpdateById(driverId)
                            .orElse(null);

            if (driver == null) {
                continue;
            }

            /* On a ride. Not this sweep's business. */
            if (driver.getAvailabilityStatus()
                    != DriverAvailabilityStatus.ONLINE) {
                continue;
            }

            /* Renewed between the query and the lock. */
            if (LicenseValidity.isValidOn(
                    driver.getLicenseExpiryDate(),
                    today
            )) {
                continue;
            }

            driver.setAvailabilityStatus(
                    DriverAvailabilityStatus.OFFLINE
            );

            driverRepository.save(driver);

            takenOffline++;

            /*
             * Logged per driver, at INFO, with the date.
             *
             * This is the only record a partner's support call has to work from
             * until the fleet screen explains it itself, and "why did my driver
             * go offline" is precisely the question it will be asked.
             */
            log.info(
                    "Driver taken offline on an expired license: driverId={} companyId={} expired={}",
                    driver.getId(),
                    driver.getCompany().getId(),
                    driver.getLicenseExpiryDate()
            );
        }

        if (takenOffline > 0) {
            log.info(
                    "License sweep took {} driver(s) offline (of {} candidate(s))",
                    takenOffline,
                    candidates.size()
            );
        }

        return takenOffline;
    }
}
