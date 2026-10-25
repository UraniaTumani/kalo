package com.kalo.driver.service;
import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** Internal one-entry reconciliation; no controller, timer, or live reader bean. */
@Service
public class DriverInvitationReportCheck {
    public enum Result { DISABLED, SKIPPED, UNRESOLVED, RESOLVED }
    private final DriverInvitationDeliveryRepository deliveries;
    private final DriverInvitationReconciliation reconciliation;
    private final ObjectProvider<DriverInvitationReportReader> readers;
    private final boolean enabled;
    public DriverInvitationReportCheck(DriverInvitationDeliveryRepository deliveries,
            DriverInvitationReconciliation reconciliation, ObjectProvider<DriverInvitationReportReader> readers,
            @Value("${app.driver-invitation.report-check-enabled:false}") boolean enabled) {
        this.deliveries = deliveries; this.reconciliation = reconciliation; this.readers = readers; this.enabled = enabled;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result check(Long deliveryId) {
        if (!enabled) return Result.DISABLED;
        var reader = readers.getIfAvailable(); if (reader == null) return Result.DISABLED;
        var queue = deliveries.findById(deliveryId).orElse(null);
        if (queue == null || queue.getState() != DriverInvitationDelivery.State.REVIEW) return Result.SKIPPED;
        String id = queue.getProviderMessageId(); String destination = queue.getDestination();
        DriverInvitationReportReader.Evidence evidence;
        try { evidence = reader.lookup(id,destination); }
        catch (RuntimeException uncertain) { return Result.UNRESOLVED; }
        if (evidence == null || evidence == DriverInvitationReportReader.Evidence.UNKNOWN) return Result.UNRESOLVED;
        reconciliation.applyVerifiedEvidence(deliveryId,id,
                evidence == DriverInvitationReportReader.Evidence.ACCEPTED
                        ? DriverInvitationReconciliation.Evidence.PROVIDER_ACCEPTED
                        : DriverInvitationReconciliation.Evidence.PROVIDER_REJECTED);
        return Result.RESOLVED;
    }
}
