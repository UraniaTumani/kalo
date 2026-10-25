package com.kalo.driver.service;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
/**
 * Internal evidence application only: NO public controller, webhook or polling.
 * Future callers must authenticate provider reports and verify their provenance,
 * messageId, destination and status before calling. A duplicate-ID error or a
 * missing report is NOT confirmed evidence. ACCEPTED still does not mean handset delivery.
 */
@Service
public class DriverInvitationReconciliation {
    public enum Evidence { PROVIDER_ACCEPTED, PROVIDER_REJECTED }
    private final DriverInvitationDeliveryRepository deliveries;
    public DriverInvitationReconciliation(DriverInvitationDeliveryRepository deliveries) { this.deliveries = deliveries; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyVerifiedEvidence(Long deliveryId, String messageId, Evidence evidence) {
        if (evidence == null) throw new IllegalArgumentException("Provider evidence is required");
        var queue = deliveries.findForUpdateById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));
        queue.reconcile(messageId, evidence == Evidence.PROVIDER_ACCEPTED);
    }
}
