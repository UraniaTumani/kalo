package com.kalo.driver.repository;

import com.kalo.driver.entity.DriverInvitationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;

public interface DriverInvitationDeliveryRepository extends JpaRepository<DriverInvitationDelivery, Long> {
    @Query("""
            SELECT d.id FROM DriverInvitationDelivery d WHERE d.state = :review
              AND d.reportStopReason IS NULL
              AND (d.nextReportCheckAt IS NULL OR d.nextReportCheckAt <= :now)
            ORDER BY COALESCE(d.nextReportCheckAt,d.createdAt),d.id
            """)
    List<Long> findReviewDueIds(@Param("review") DriverInvitationDelivery.State review,
            @Param("now") Instant now, Pageable pageable);

    /** Current key only for sends; invalid invitations are included for payload cleanup. */
    @Query("""
            SELECT d.id FROM DriverInvitationDelivery d
            WHERE d.state = :pending AND (
              (d.keyId = :keyId AND d.nextAttemptAt <= :now)
              OR d.invitation.expiresAt <= :now
              OR d.invitation.revokedAt IS NOT NULL
              OR d.invitation.redeemedAt IS NOT NULL)
            ORDER BY d.nextAttemptAt, d.id
            """)
    List<Long> findDueIds(@Param("pending") DriverInvitationDelivery.State pending,
            @Param("keyId") String keyId, @Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query("""
            UPDATE DriverInvitationDelivery d SET d.state = :cancelled, d.encryptedPayload = NULL
            WHERE d.state = :pending AND d.invitation.id IN
              (SELECT i.id FROM DriverInvitation i WHERE i.driver.id = :driverId)
            """)
    int cancelPendingForDriver(@Param("driverId") Long driverId,
            @Param("pending") DriverInvitationDelivery.State pending,
            @Param("cancelled") DriverInvitationDelivery.State cancelled);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DriverInvitationDelivery d WHERE d.id = :id")
    Optional<DriverInvitationDelivery> findForUpdateById(@Param("id") Long id);
}
