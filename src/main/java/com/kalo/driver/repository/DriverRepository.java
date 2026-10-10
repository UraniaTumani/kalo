package com.kalo.driver.repository;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DriverRepository
        extends JpaRepository<Driver, Long> {

    /**
     * Company scoping is part of the query, not a caller's responsibility: a
     * partner must never be able to page into another company's fleet.
     *
     * Both filters are optional. The dropdowns that pick a driver ask for
     * ACTIVE + ONLINE, which is smaller and more correct than fetching the
     * whole fleet and filtering in the browser.
     */
    @Query("""
            SELECT d
            FROM Driver d
            WHERE d.company.id = :companyId
              AND (:status IS NULL OR d.status = :status)
              AND (:availabilityStatus IS NULL OR d.availabilityStatus = :availabilityStatus)
              AND (:unassigned IS NULL OR :unassigned = FALSE OR NOT EXISTS (
                    SELECT 1
                    FROM DriverVehicleAssignment a
                    WHERE a.driver.id = d.id
                      AND a.active = TRUE
                  ))
            """)
    Page<Driver> findAllByCompanyIdFiltered(
            @Param("companyId") Long companyId,
            @Param("status") DriverStatus status,
            @Param("availabilityStatus") DriverAvailabilityStatus availabilityStatus,
            @Param("unassigned") Boolean unassigned,
            Pageable pageable
    );

    Optional<Driver> findByIdAndCompanyId(
            Long driverId,
            Long companyId
    );

    boolean existsByPhone(
            String phone
    );

    boolean existsByLicenseNumber(
            String licenseNumber
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT d
            FROM Driver d
            WHERE d.id = :driverId
            AND d.company.id = :companyId
            """)
    Optional<Driver> findForUpdateByIdAndCompanyId(
            @Param("driverId") Long driverId,
            @Param("companyId") Long companyId
    );

    /**
     * Ids of drivers who are ONLINE on an expired licence (F36).
     *
     * ONLINE only, deliberately. A BUSY driver is on a ride, and a licence that
     * lapsed mid-journey must not reach into that ride — the passenger is
     * already in the car. The sweep's job is to stop the NEXT assignment, which
     * means the only state it has any business changing is the one that offers a
     * driver up for one.
     *
     * Ids rather than entities: each is re-read under a lock before anything is
     * written, so loading the rows here would only be loading them twice.
     */
    @Query("""
            SELECT d.id
            FROM Driver d
            WHERE d.availabilityStatus = :onlineStatus
            AND d.licenseExpiryDate < :today
            """)
    List<Long> findIdsOnlineWithLicenseExpiredBefore(
            @Param("onlineStatus") DriverAvailabilityStatus onlineStatus,
            @Param("today") LocalDate today
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT d
            FROM Driver d
            WHERE d.id = :driverId
            """)
    Optional<Driver> findForUpdateById(
            @Param("driverId") Long driverId
    );
}