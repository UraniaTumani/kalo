package com.kalo.ride.repository;

import com.kalo.assignment.entity.DriverVehicleAssignment;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.vehicle.enums.VehicleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read side of the taxi search. Rooted at the active driver/vehicle assignment
 * so driver, vehicle, company and position come back in one query instead of
 * one assignment lookup per candidate driver.
 */
public interface TaxiSearchRepository
        extends JpaRepository<DriverVehicleAssignment, Long> {

    @Query("""
            SELECT new com.kalo.ride.repository.AvailableTaxiRow(
                c,
                d,
                v,
                dl.latitude,
                dl.longitude
            )
            FROM DriverVehicleAssignment a
            JOIN a.driver d
            JOIN a.vehicle v
            JOIN d.company c
            JOIN DriverLocation dl
                ON dl.driver = d
            WHERE a.active = true
              AND d.status = :driverStatus
              AND d.availabilityStatus = :availabilityStatus
              AND v.status = :vehicleStatus
              AND c.verificationStatus = :verificationStatus
              AND c.status = :companyStatus
              AND c.bookingEnabled = true
              AND dl.locationUpdatedAt >= :minimumLocationTime
              AND d.licenseExpiryDate >= :today
              AND c.licenseExpiryDate IS NOT NULL
              AND c.licenseExpiryDate >= :today
            """)
    List<AvailableTaxiRow> findAvailableTaxis(
            @Param("driverStatus")
            DriverStatus driverStatus,

            @Param("availabilityStatus")
            DriverAvailabilityStatus availabilityStatus,

            @Param("vehicleStatus")
            VehicleStatus vehicleStatus,

            @Param("verificationStatus")
            VerificationStatus verificationStatus,

            @Param("companyStatus")
            CompanyStatus companyStatus,

            @Param("minimumLocationTime")
            Instant minimumLocationTime,

            /*
             * Today in Europe/Tirane, supplied by the caller rather than read
             * here.
             *
             * Passed in for two reasons. The database's own CURRENT_DATE is the
             * container's zone, which is not the zone a licence is judged in;
             * and one search must use a single date for every row it considers,
             * rather than re-evaluating it per predicate.
             *
             * Both licence predicates are extra conditions on joins the query
             * already makes, so enforcing this costs no additional query and no
             * additional round trip (F36).
             */
            @Param("today")
            LocalDate today
    );
}
