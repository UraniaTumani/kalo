package com.kalo.ride.repository;

import com.kalo.ride.entity.Ride;
import com.kalo.ride.enums.RideStatus;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.Optional;

public interface DriverCurrentRideRepository extends Repository<Ride, Long> {
    @Query("""
        SELECT r FROM Ride r
        JOIN FETCH r.rideRequest
        LEFT JOIN FETCH r.vehicle
        JOIN r.driver d JOIN d.user u JOIN d.company c
        WHERE d.id = :driverId AND r.company.id = :companyId AND c.id = :companyId
          AND u.phone = :phone AND d.phone = u.phone AND u.phoneVerified = true
          AND u.role = com.kalo.user.enums.UserRole.DRIVER
          AND u.status = com.kalo.user.enums.UserStatus.ACTIVE
          AND d.status = com.kalo.driver.enums.DriverStatus.ACTIVE
          AND c.status = com.kalo.partner.enums.CompanyStatus.ACTIVE
          AND c.verificationStatus = com.kalo.partner.enums.VerificationStatus.APPROVED
          AND r.status IN :statuses
        """)
    Optional<Ride> findCurrent(@Param("driverId") Long driverId, @Param("companyId") Long companyId,
            @Param("phone") String phone, @Param("statuses") Collection<RideStatus> statuses);
}
