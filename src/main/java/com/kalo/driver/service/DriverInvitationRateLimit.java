package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.repository.DriverInvitationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Internal precondition for future issuance, not a reservation of quota.
 * The caller must save the invitation in the SAME write transaction. Authorization
 * holds the company and driver locks until that transaction ends, serializing
 * competing issuance attempts even on different application instances.
 * No delivery, token persistence or public endpoint is enabled by this step.
 */
@Component
public class DriverInvitationRateLimit {
    private final DriverInvitationAuthorization authorization;
    private final DriverInvitationRepository invitations;
    private final Duration cooldown;
    private final Duration window;
    private final int companyLimit;

    public DriverInvitationRateLimit(
            DriverInvitationAuthorization authorization,
            DriverInvitationRepository invitations,
            @Value("${app.driver-invitation.cooldown-seconds:60}") long cooldownSeconds,
            @Value("${app.driver-invitation.company-window-seconds:3600}") long windowSeconds,
            @Value("${app.driver-invitation.company-limit:20}") int companyLimit) {
        if (cooldownSeconds <= 0 || windowSeconds <= 0 || companyLimit <= 0) {
            throw new IllegalArgumentException("Driver invitation limits must be positive");
        }
        this.authorization = authorization;
        this.invitations = invitations;
        this.cooldown = Duration.ofSeconds(cooldownSeconds);
        this.window = Duration.ofSeconds(windowSeconds);
        this.companyLimit = companyLimit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Driver requireAllowedDriver(Long driverId) {
        Driver driver = authorization.requireEligibleDriver(driverId);
        // Sample time after acquiring locks: lock wait must not shorten cooldown.
        validate(driver, Instant.now());
        return driver;
    }

    // Package-private for deterministic boundary tests; callers use the authorized method.
    void validate(Driver driver, Instant now) {
        invitations.findFirstByDriverIdOrderByCreatedAtDesc(driver.getId())
                .ifPresent(latest -> {
                    if (latest.getCreatedAt() == null
                            || latest.getCreatedAt().isAfter(now.minus(cooldown))) {
                        throw new InvalidOperationException("Driver invitation cooldown is active");
                    }
                });
        if (invitations.countByCompanyIdAndCreatedAtGreaterThanEqual(
                driver.getCompany().getId(), now.minus(window)) >= companyLimit) {
            throw new InvalidOperationException("Company invitation limit reached");
        }
    }
}
