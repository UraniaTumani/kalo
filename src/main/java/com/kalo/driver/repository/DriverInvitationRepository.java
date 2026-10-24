package com.kalo.driver.repository;

import com.kalo.driver.entity.DriverInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface DriverInvitationRepository extends JpaRepository<DriverInvitation, Long> {

    /**
     * Redemption will lock the invitation row before checking expiry, use,
     * company ownership and linked driver identity. Delivery remains disabled.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM DriverInvitation i WHERE i.tokenHash = :hash")
    Optional<DriverInvitation> findForUpdateByTokenHash(@Param("hash") String hash);
}
