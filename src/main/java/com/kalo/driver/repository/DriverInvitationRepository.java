package com.kalo.driver.repository;

import com.kalo.driver.entity.DriverInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;

public interface DriverInvitationRepository extends JpaRepository<DriverInvitation, Long> {

    /**
     * Redemption will lock the invitation row before checking expiry, use,
     * company ownership and linked driver identity. Delivery remains disabled.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM DriverInvitation i WHERE i.tokenHash = :hash")
    Optional<DriverInvitation> findForUpdateByTokenHash(@Param("hash") String hash);

    /** Cooldown applies to the driver, regardless of request IP. */
    Optional<DriverInvitation> findFirstByDriverIdOrderByCreatedAtDesc(Long driverId);

    /** Revoke all unredeemed invitation rows for the driver, including expired. */
    @Modifying
    @Query("""
            UPDATE DriverInvitation i SET i.revokedAt = :now
            WHERE i.driver.id = :driverId
              AND i.redeemedAt IS NULL
              AND i.revokedAt IS NULL
            """)
    int revokeUnredeemedForDriver(
            @Param("driverId") Long driverId,
            @Param("now") Instant now);
}
