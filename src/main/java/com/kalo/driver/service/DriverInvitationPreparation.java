package com.kalo.driver.service;

import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * Atomic internal preparation. No controller, scheduler, or real cipher bean is
 * enabled. Future wiring must supply an externally managed key and a key ID,
 * and validate contact ownership and current eligibility before actual sending.
 * Raw token exists only in memory; neither result nor exceptions include it.
 */
@Service
public class DriverInvitationPreparation {
    private final DriverInvitationPersistence persistence;
    private final DriverInvitationRepository invitations;
    private final DriverInvitationDeliveryRepository deliveries;
    private final DriverInvitationTokenCodec codec;
    private final ObjectProvider<DriverInvitationDeliveryCipher> ciphers;
    private final String keyId;

    public DriverInvitationPreparation(DriverInvitationPersistence persistence,
            DriverInvitationRepository invitations, DriverInvitationDeliveryRepository deliveries,
            DriverInvitationTokenCodec codec, ObjectProvider<DriverInvitationDeliveryCipher> ciphers,
            @Value("${app.driver-invitation.delivery-key-id:}") String keyId) {
        this.persistence = persistence;
        this.invitations = invitations;
        this.deliveries = deliveries;
        this.codec = codec;
        this.ciphers = ciphers;
        this.keyId = keyId;
    }

    @Transactional
    public Long prepare(Long driverId) {
        DriverInvitationDeliveryCipher cipher = ciphers.getIfAvailable();
        if (cipher == null || keyId == null || keyId.isBlank() || keyId.length() > 100) {
            throw new IllegalStateException("Invitation delivery encryption is not configured");
        }
        String token = codec.generate();
        Long invitationId = persistence.stageHash(driverId, codec.digest(token));
        DriverInvitation invitation = invitations.findById(invitationId).orElseThrow();
        String phone = invitation.getDriver().getPhone();
        String encrypted = cipher.encrypt(token, invitationId, invitation.getDriver().getId(),
                phone, invitation.getExpiresAt());
        deliveries.cancelPendingForDriver(driverId,
                DriverInvitationDelivery.State.PENDING, DriverInvitationDelivery.State.CANCELLED);
        deliveries.saveAndFlush(new DriverInvitationDelivery(invitation, phone, keyId, encrypted, Instant.now()));
        return invitationId;
    }
}
