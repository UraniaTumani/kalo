package com.kalo.driver.service;
import com.kalo.driver.entity.DriverInvitation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DriverInvitationIssuerTest {
    @Test void recordsIssuerOnceAndRejectsReplacement() {
        var invitation = new DriverInvitation(); invitation.recordIssuer(10L);
        assertThat(invitation.getIssuedByUserId()).isEqualTo(10L);
        assertThatThrownBy(() -> invitation.recordIssuer(11L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(invitation.getIssuedByUserId()).isEqualTo(10L);
    }
    @Test void refusesMissingOrNonPositiveIssuer() {
        for (Long id : new Long[]{null,0L,-1L}) {
            var invitation = new DriverInvitation();
            assertThatThrownBy(() -> invitation.recordIssuer(id)).isInstanceOf(IllegalArgumentException.class);
            assertThat(invitation.getIssuedByUserId()).isNull();
        }
    }
}
