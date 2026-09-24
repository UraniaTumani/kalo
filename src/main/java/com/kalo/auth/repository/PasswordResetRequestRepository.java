package com.kalo.auth.repository;

import com.kalo.auth.entity.PasswordResetRequest;
import com.kalo.auth.enums.PasswordResetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetRequestRepository
        extends JpaRepository<PasswordResetRequest, Long> {

    /**
     * The one request a redemption could possibly match.
     *
     * Bcrypt salts every hash differently, so unlike a refresh token this
     * cannot be looked up by its own value — the caller names the account and
     * the code is then checked against what is stored. Newest first, because
     * asking again supersedes.
     */
    Optional<PasswordResetRequest> findFirstByUserIdAndStatusOrderByCreatedAtDesc(
            Long userId,
            PasswordResetStatus status
    );

    /** The queue an administrator works through, oldest first. */
    Page<PasswordResetRequest> findAllByStatusOrderByCreatedAtAsc(
            PasswordResetStatus status,
            Pageable pageable
    );

    /**
     * How often this account has asked lately.
     *
     * Rate limiting by IP alone does not stop somebody spreading requests for
     * one victim across many addresses, which would flood the admin queue until
     * a tired administrator approved one.
     */
    @Query("""
            SELECT count(r)
            FROM PasswordResetRequest r
            WHERE r.user.id = :userId
              AND r.createdAt > :since
            """)
    long countRecentForUser(
            @Param("userId") Long userId,
            @Param("since") Instant since
    );

    /**
     * Retires anything still open for a user, so one account never has two
     * live codes and a successful reset cancels whatever else was in flight.
     */
    @Modifying
    @Query("""
            UPDATE PasswordResetRequest r
            SET r.status = com.kalo.auth.enums.PasswordResetStatus.REJECTED,
                r.completedAt = :now
            WHERE r.user.id = :userId
              AND r.status IN (
                    com.kalo.auth.enums.PasswordResetStatus.PENDING,
                    com.kalo.auth.enums.PasswordResetStatus.ISSUED
              )
            """)
    int rejectAllOpenForUser(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );
}
