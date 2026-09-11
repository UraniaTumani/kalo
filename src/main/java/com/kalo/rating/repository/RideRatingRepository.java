package com.kalo.rating.repository;

import com.kalo.rating.entity.RideRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideRatingRepository
        extends JpaRepository<RideRating, Long> {

    boolean existsByRideId(
            Long rideId
    );

    @Query("""
            SELECT AVG(r.driverRating)
            FROM RideRating r
            WHERE r.driver.id = :driverId
            """)
    Double calculateAverageDriverRating(
            @Param("driverId") Long driverId
    );

    long countByDriverId(
            Long driverId
    );

    @Query("""
            SELECT AVG(r.companyRating)
            FROM RideRating r
            WHERE r.company.id = :companyId
            """)
    Double calculateAverageCompanyRating(
            @Param("companyId") Long companyId
    );

    long countByCompanyId(
            Long companyId
    );
}