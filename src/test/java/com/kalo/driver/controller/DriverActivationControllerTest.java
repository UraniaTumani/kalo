package com.kalo.driver.controller;

import com.kalo.driver.service.DriverInvitationRedemption;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DriverActivationControllerTest {
    @Test void apiCannotStartWithActivationDisabled() {
        var service=mock(DriverInvitationRedemption.class);
        assertThatThrownBy(()->new DriverActivationController(service,false))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(service);
    }
    @Test void requestStringDoesNotExposeCredentials() {
        assertThat(new DriverActivationController.Request("secret-token","secret-password").toString())
                .doesNotContain("secret-token","secret-password");
    }
    @Test void apiCanBeConstructedWhenActivationIsExplicitlyEnabled() {
        var service=mock(DriverInvitationRedemption.class);
        assertThat(new DriverActivationController(service,true)).isNotNull();verifyNoInteractions(service);
    }
}
