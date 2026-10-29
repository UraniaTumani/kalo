package com.kalo.ride.repository;

import com.kalo.ride.entity.Ride;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface DriverRideActionRepository extends Repository<Ride, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Ride r WHERE r.id = :rideId AND r.driver.id = :driverId AND r.company.id = :companyId")
    Optional<Ride> lockOwned(@Param("rideId") Long rideId, @Param("driverId") Long driverId, @Param("companyId") Long companyId);
}
