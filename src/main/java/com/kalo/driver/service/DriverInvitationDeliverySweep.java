package com.kalo.driver.service;

import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.Instant;

/** Bounded scan; the proxied worker commits each entry independently. */
@Service
public class DriverInvitationDeliverySweep {
    private final DriverInvitationDeliveryRepository deliveries;
    private final DriverInvitationDeliveryWorker worker;
    private final int batchSize;
    private final String keyId;
    public DriverInvitationDeliverySweep(DriverInvitationDeliveryRepository deliveries,
            DriverInvitationDeliveryWorker worker,
            @Value("${app.driver-invitation.batch-size:10}") int batchSize,
            @Value("${app.driver-invitation.delivery-key-id:}") String keyId) {
        if (batchSize < 1 || batchSize > 50) throw new IllegalArgumentException("Invitation batch size must be between 1 and 50");
        this.deliveries = deliveries; this.worker = worker; this.batchSize = batchSize; this.keyId = keyId;
    }
    public void processDue() {
        var ids = deliveries.findDueIds(DriverInvitationDelivery.State.PENDING, keyId,
                Instant.now(), PageRequest.of(0, batchSize));
        for (Long id : ids) {
            // Propagate unexpected failures to the scheduler's sanitized handler.
            // Previous attempts already committed in REQUIRES_NEW transactions.
            worker.process(id);
        }
    }
}
