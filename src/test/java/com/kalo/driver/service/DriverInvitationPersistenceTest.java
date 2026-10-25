package com.kalo.driver.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriverInvitationPersistenceTest {
    private final DriverInvitationRateLimit limits = mock(DriverInvitationRateLimit.class);
    private final DriverInvitationRepository invitations = mock(DriverInvitationRepository.class);
    private final DriverInvitationPersistence persistence = new DriverInvitationPersistence(limits, invitations, 86400);

    @Test
    void checksLimitsThenRevokesThenStoresOnlyDigestWithExpiry() {
        TaxiCompany company = new TaxiCompany();
        company.setId(10L);
        User owner = new User(); owner.setId(40L); company.setOwner(owner);
        Driver driver = new Driver();
        driver.setId(20L);
        driver.setCompany(company);
        when(limits.requireAllowedDriver(20L)).thenReturn(driver);
        when(invitations.saveAndFlush(any())).thenAnswer(call -> {
            DriverInvitation saved = call.getArgument(0);
            saved.setId(30L);
            return saved;
        });
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(persistence.stageHash(20L, "a".repeat(64))).isEqualTo(30L);
        var order = inOrder(limits, invitations);
        order.verify(limits).requireAllowedDriver(20L);
        order.verify(invitations).revokeUnredeemedForDriver(eq(20L), any());
        ArgumentCaptor<DriverInvitation> captor = ArgumentCaptor.forClass(DriverInvitation.class);
        order.verify(invitations).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo("a".repeat(64));
        assertThat(captor.getValue().getCompany()).isSameAs(company);
        assertThat(captor.getValue().getIssuedByUserId()).isEqualTo(40L);
        assertThat(captor.getValue().getExpiresAt().getNano() % 1000).isZero();
        assertThat(captor.getValue().getExpiresAt()).isBetween(before.plusSeconds(86400), Instant.now().plusSeconds(86400));
    }

    @Test
    void rejectedAuthorizationOrQuotaNeverMutatesInvitations() {
        when(limits.requireAllowedDriver(20L)).thenThrow(new InvalidOperationException("Rejected"));
        assertThatThrownBy(() -> persistence.stageHash(20L, "a".repeat(64)))
                .isInstanceOf(InvalidOperationException.class);
        verifyNoInteractions(invitations);
    }

    @Test
    void rejectsMalformedDigestsWithoutExposingInput() {
        for (String invalid : new String[]{null, "secret", "A".repeat(64), "g".repeat(64)}) {
            assertThatThrownBy(() -> persistence.stageHash(20L, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid invitation digest");
        }
        verifyNoInteractions(limits, invitations);
    }

    @Test
    void rejectsNonPositiveLifetime() {
        assertThatThrownBy(() -> new DriverInvitationPersistence(limits, invitations, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
