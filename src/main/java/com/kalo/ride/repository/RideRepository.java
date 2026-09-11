package com.kalo.ride.repository;

import com.kalo.ride.entity.Ride;
import com.kalo.ride.enums.RideStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RideRepository
        extends JpaRepository<Ride, Long> {

    /*
     * Prevent selecting the same taxi company
     * more than once for the same RideRequest.
     */
    boolean existsByRideRequestIdAndCompanyId(
            Long rideRequestId,
            Long companyId
    );

    /*
     * Used to prevent a customer from having
     * multiple active rides at the same time.
     */
    boolean existsByCustomerIdAndStatusIn(
            Long customerId,
            List<RideStatus> statuses
    );

    /*
     * Customer ownership.
     */
    Optional<Ride> findByIdAndCustomerId(
            Long rideId,
            Long customerId
    );

    /*
     * Partner/company ownership.
     */
    Optional<Ride> findByIdAndCompanyId(
            Long rideId,
            Long companyId
    );

    /*
     * All rides belonging to a taxi company.
     */
    List<Ride> findAllByCompanyId(
            Long companyId
    );

    /*
     * Filter partner rides by status.
     */
    List<Ride> findAllByCompanyIdAndStatus(
            Long companyId,
            RideStatus status
    );

    /*
     * Customer ride history.
     */
    List<Ride> findAllByCustomerIdOrderByRequestedAtDesc(
            Long customerId
    );

    /*
     * Customer current active ride.
     */
    Optional<Ride> findFirstByCustomerIdAndStatusInOrderByRequestedAtDesc(
            Long customerId,
            List<RideStatus> statuses
    );

    /*
     * Lock ride when partner performs:
     * ACCEPT / DECLINE / lifecycle changes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM Ride r
            WHERE r.id = :rideId
            AND r.company.id = :companyId
            """)
    Optional<Ride> findForUpdateByIdAndCompanyId(
            @Param("rideId") Long rideId,
            @Param("companyId") Long companyId
    );

    /*
     * Lock ride when customer performs
     * operations such as cancellation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM Ride r
            WHERE r.id = :rideId
            AND r.customer.id = :customerId
            """)
    Optional<Ride> findForUpdateByIdAndCustomerId(
            @Param("rideId") Long rideId,
            @Param("customerId") Long customerId
    );

    /*
     * Used by the timeout scheduler.
     *
     * First we retrieve candidate Ride IDs
     * that have been REQUESTED for too long.
     */
    @Query("""
            SELECT r.id
            FROM Ride r
            WHERE r.status = :status
            AND r.requestedAt < :requestedBefore
            """)
    List<Long> findIdsByStatusAndRequestedAtBefore(
            @Param("status") RideStatus status,
            @Param("requestedBefore") Instant requestedBefore
    );

    /*
     * Scheduler locks each Ride before changing
     * REQUESTED -> NO_RESPONSE.
     *
     * This prevents races with partner ACCEPT/DECLINE.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM Ride r
            WHERE r.id = :rideId
            """)
    Optional<Ride> findForUpdateById(
            @Param("rideId") Long rideId
    );





}