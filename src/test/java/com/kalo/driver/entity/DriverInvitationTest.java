package com.kalo.driver.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DriverInvitationTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Test
    void validUnusedInvitationCanBeRedeemed() {
        DriverInvitation invitation = invitation(NOW.plusSeconds(60));
        assertThat(invitation.isRedeemable(NOW)).isTrue();
    }

    @Test
    void expiredInvitationCannotBeRedeemed() {
        DriverInvitation invitation = invitation(NOW);
        assertThat(invitation.isRedeemable(NOW)).isFalse();
    }

    @Test
    void redeemedInvitationCannotBeUsedAgain() {
        DriverInvitation invitation = invitation(NOW.plusSeconds(60));
        invitation.setRedeemedAt(NOW.minusSeconds(1));
        assertThat(invitation.isRedeemable(NOW)).isFalse();
    }

    @Test
    void revokedInvitationCannotBeUsed() {
        DriverInvitation invitation = invitation(NOW.plusSeconds(60));
        invitation.setRevokedAt(NOW.minusSeconds(1));
        assertThat(invitation.isRedeemable(NOW)).isFalse();
    }

    private DriverInvitation invitation(Instant expiresAt) {
        DriverInvitation invitation = new DriverInvitation();
        invitation.setExpiresAt(expiresAt);
        return invitation;
    }
}
