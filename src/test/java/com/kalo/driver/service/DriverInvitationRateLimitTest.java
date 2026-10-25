package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.partner.entity.TaxiCompany;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriverInvitationRateLimitTest {
    private final DriverInvitationAuthorization authorization = mock(DriverInvitationAuthorization.class);
    private final DriverInvitationRepository invitations = mock(DriverInvitationRepository.class);
    private final DriverInvitationRateLimit limits = new DriverInvitationRateLimit(
            authorization, invitations, 60, 3600, 20);
    private final Instant now = Instant.parse("2026-10-24T17:00:00Z");

    @Test
    void permitsFirstInvitationBelowCompanyLimit() {
        assertThatCode(() -> limits.validate(driver(), now)).doesNotThrowAnyException();
        verify(invitations).countByCompanyIdAndCreatedAtGreaterThanEqual(10L, now.minusSeconds(3600));
    }

    @Test
    void rejectsCooldownEvenForRevokedInvitation() {
        DriverInvitation previous = previous(now.minusSeconds(59));
        previous.setRevokedAt(now.minusSeconds(1));
        assertThatThrownBy(() -> limits.validate(driver(), now))
                .isInstanceOf(InvalidOperationException.class).hasMessage("Driver invitation cooldown is active");
        verify(invitations, never()).countByCompanyIdAndCreatedAtGreaterThanEqual(anyLong(), any());
    }

    @Test
    void permitsExactlyAtCooldownBoundary() {
        previous(now.minusSeconds(60));
        assertThatCode(() -> limits.validate(driver(), now)).doesNotThrowAnyException();
    }

    @Test
    void rejectsFutureAndMissingCreationTime() {
        previous(now.plusSeconds(10));
        assertThatThrownBy(() -> limits.validate(driver(), now)).isInstanceOf(InvalidOperationException.class);
        previous(null);
        assertThatThrownBy(() -> limits.validate(driver(), now)).isInstanceOf(InvalidOperationException.class);
    }

    @Test
    void rejectsAtAndAboveCompanyQuota() {
        when(invitations.countByCompanyIdAndCreatedAtGreaterThanEqual(10L, now.minusSeconds(3600)))
                .thenReturn(20L, 21L);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> limits.validate(driver(), now))
                    .isInstanceOf(InvalidOperationException.class).hasMessage("Company invitation limit reached");
        }
    }

    @Test
    void authorizesAndLocksBeforeReadingQuota() {
        Driver driver = driver();
        when(authorization.requireEligibleDriver(20L)).thenReturn(driver);
        assertThat(limits.requireAllowedDriver(20L)).isSameAs(driver);
        var order = inOrder(authorization, invitations);
        order.verify(authorization).requireEligibleDriver(20L);
        order.verify(invitations).findFirstByDriverIdOrderByCreatedAtDesc(20L);
        order.verify(invitations).countByCompanyIdAndCreatedAtGreaterThanEqual(eq(10L), any());
    }

    @Test
    void unauthorizedRequestsNeverReadInvitationHistory() {
        doThrow(new InvalidOperationException("Not eligible"))
                .when(authorization).requireEligibleDriver(20L);
        assertThatThrownBy(() -> limits.requireAllowedDriver(20L)).isInstanceOf(InvalidOperationException.class);
        verifyNoInteractions(invitations);
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new DriverInvitationRateLimit(authorization, invitations, 0, 3600, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DriverInvitationRateLimit(authorization, invitations, 60, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DriverInvitationRateLimit(authorization, invitations, 60, 3600, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Driver driver() {
        TaxiCompany company = new TaxiCompany();
        company.setId(10L);
        Driver driver = new Driver();
        driver.setId(20L);
        driver.setCompany(company);
        return driver;
    }

    private DriverInvitation previous(Instant createdAt) {
        DriverInvitation invitation = new DriverInvitation();
        invitation.setCreatedAt(createdAt);
        when(invitations.findFirstByDriverIdOrderByCreatedAtDesc(20L)).thenReturn(Optional.of(invitation));
        return invitation;
    }
}
