package com.kalo.driver.repository;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DriverRepository
        extends JpaRepository<Driver, Long> {

    List<Driver> findAllByCompanyId(
            Long companyId
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

    List<Driver> findAllByCompanyIdAndAvailabilityStatus(
            Long companyId,
            DriverAvailabilityStatus availabilityStatus
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
}