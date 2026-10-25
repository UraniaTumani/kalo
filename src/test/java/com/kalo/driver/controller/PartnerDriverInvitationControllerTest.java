package com.kalo.driver.controller;

import com.kalo.driver.service.DriverInvitationDeliveryCipher;
import com.kalo.driver.service.DriverInvitationPreparation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PartnerDriverInvitationControllerTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<DriverInvitationDeliveryCipher> provider(boolean configured) {
        ObjectProvider<DriverInvitationDeliveryCipher> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configured ? new DriverInvitationDeliveryCipher(new byte[32]) : null);
        return provider;
    }
    @Test void startupRejectsMissingCipher() {
        var preparation=mock(DriverInvitationPreparation.class);
        assertThatThrownBy(()->new PartnerDriverInvitationController(preparation,provider(false),"key"))
                .isInstanceOf(IllegalStateException.class);verifyNoInteractions(preparation);
    }
    @Test void startupRejectsInvalidKeyIds() {
        var preparation=mock(DriverInvitationPreparation.class);
        for(String key:new String[]{null,""," ","k".repeat(101)})
            assertThatThrownBy(()->new PartnerDriverInvitationController(preparation,provider(true),key))
                    .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(preparation);
    }
    @Test void queuesOnlyRequestedDriverAndReturnsEmptyAcceptedResponse() {
        var preparation=mock(DriverInvitationPreparation.class);
        var controller=new PartnerDriverInvitationController(preparation,provider(true),"test-key");
        var response=controller.invite(42L);verify(preparation).prepare(42L);
        assertThat(response.getStatusCode().value()).isEqualTo(202);assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
