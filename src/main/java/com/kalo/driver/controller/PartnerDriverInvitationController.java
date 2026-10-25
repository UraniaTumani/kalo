package com.kalo.driver.controller;

import com.kalo.driver.service.DriverInvitationDeliveryCipher;
import com.kalo.driver.service.DriverInvitationPreparation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Queues an invitation atomically. Acceptance does not promise SMS delivery. */
@RestController
@ConditionalOnProperty(name="app.driver-invitation.issuance-api-enabled", havingValue="true")
public class PartnerDriverInvitationController {
    private final DriverInvitationPreparation preparation;

    public PartnerDriverInvitationController(DriverInvitationPreparation preparation,
            ObjectProvider<DriverInvitationDeliveryCipher> ciphers,
            @Value("${app.driver-invitation.delivery-key-id:}") String keyId) {
        if (ciphers.getIfAvailable()==null || keyId==null || keyId.isBlank() || keyId.length()>100)
            throw new IllegalStateException("Driver invitation API requires delivery encryption configuration");
        this.preparation=preparation;
    }

    @PostMapping("/api/v1/partner/drivers/{driverId}/invitations")
    public ResponseEntity<Void> invite(@PathVariable("driverId") Long driverId) {
        // Authorization, tenant locks, eligibility, quotas and issuer identity
        // are derived from the current principal by the transactional service.
        preparation.prepare(driverId);
        return ResponseEntity.accepted().header("Cache-Control","no-store").build();
    }
}
