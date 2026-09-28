package com.kalo.auth.repository;

import com.kalo.auth.entity.PasswordResetRequest;
import com.kalo.auth.enums.PasswordResetChannel;
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

    /** The newest request for an account whatever became of it, for the cooldown. */
    Optional<PasswordResetRequest> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * How often this account has asked lately.
     *
     * Rate limiting by IP alone does not stop somebody spreading requests for
     * one victim across many addresses. That used to matter because it would
     * flood an admin queue; it matters more now, because every request that
     * gets through spends money and rings somebody's phone. An unknown number
     * creates no row and so counts for nothing here, which is correct: no
     * message was sent for it either.
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
     * Every message this deployment has sent today.
     *
     * The global ceiling, and the only defence against the attack that costs
     * real money rather than access: SMS pumping, where somebody farms traffic
     * to expensive ranges through whatever endpoint will send it. Counts SMS
     * rows only — a fallback code is not a message and must not eat the budget.
     *
     * expiresAt is the tell that a message actually went out: a request refused
     * for a suspended account is recorded without a code and so without an
     * expiry, and must not be charged against a budget it never spent.
     */
    @Query("""
            SELECT count(r)
            FROM PasswordResetRequest r
            WHERE r.channel = :channel
              AND r.expiresAt IS NOT NULL
              AND r.createdAt > :since
            """)
    long countMessagesSentSince(
            @Param("channel") PasswordResetChannel channel,
            @Param("since") Instant since
    );

    /**
     * The fallback audit trail, newest first.
     *
     * Its own query rather than a filter on a general listing, because this is
     * the only view of the table anybody reads deliberately: a run of
     * administrator-issued codes is the thing worth noticing, and burying it
     * among thousands of ordinary SMS rows would be a good way not to.
     */
    Page<PasswordResetRequest> findAllByChannelOrderByCreatedAtDesc(
            PasswordResetChannel channel,
            Pageable pageable
    );

    /**
     * Retires anything still live for a user, so one account never has two
     * usable codes and a successful reset cancels whatever else was in flight.
     */
    @Modifying
    @Query("""
            UPDATE PasswordResetRequest r
            SET r.status = com.kalo.auth.enums.PasswordResetStatus.REJECTED,
                r.completedAt = :now,
                r.codeHash = null
            WHERE r.user.id = :userId
              AND r.status = com.kalo.auth.enums.PasswordResetStatus.ACTIVE
            """)
    int rejectAllOpenForUser(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );
}
