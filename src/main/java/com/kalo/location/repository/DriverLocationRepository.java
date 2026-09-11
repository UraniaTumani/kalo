package com.kalo.location.repository;

import com.kalo.location.entity.DriverLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

import java.util.List;

public interface DriverLocationRepository
        extends JpaRepository<DriverLocation, Long> {

    Optional<DriverLocation> findByDriverId(
            Long driverId
    );

    Optional<DriverLocation> findByDriverIdAndDriverCompanyId(
            Long driverId,
            Long companyId
    );





    @Query("""
        SELECT dl
        FROM DriverLocation dl
        JOIN FETCH dl.driver d
        JOIN FETCH d.company c
        WHERE d.availabilityStatus = :availabilityStatus
        AND d.status = :driverStatus
        AND c.verificationStatus = :verificationStatus
        AND c.status = :companyStatus
        AND dl.locationUpdatedAt >= :minimumLocationTime
        """)
    List<DriverLocation> findAvailableDriverLocations(
            @Param("availabilityStatus")
            DriverAvailabilityStatus availabilityStatus,

            @Param("driverStatus")
            DriverStatus driverStatus,

            @Param("verificationStatus")
            VerificationStatus verificationStatus,

            @Param("companyStatus")
            CompanyStatus companyStatus,

            @Param("minimumLocationTime")
            Instant minimumLocationTime
    );
}