package com.kalo.driver.service;
import com.kalo.driver.entity.DriverInvitationDelivery;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.Instant;
/** Bounded review scan; no send or activation is performed. */
@Service
public class DriverInvitationReportSweep {
    private final DriverInvitationDeliveryRepository deliveries;
    private final DriverInvitationReportReservation reservations;
    private final DriverInvitationReportCheck checks;
    private final int batchSize;
    public DriverInvitationReportSweep(DriverInvitationDeliveryRepository deliveries,
            DriverInvitationReportReservation reservations, DriverInvitationReportCheck checks,
            @Value("${app.driver-invitation.report-batch-size:10}") int batchSize) {
        if (batchSize < 1 || batchSize > 50) throw new IllegalArgumentException("Report batch size must be between 1 and 50");
        this.deliveries = deliveries; this.reservations = reservations; this.checks = checks; this.batchSize = batchSize;
    }
    public void processDue() {
        var ids = deliveries.findReviewDueIds(DriverInvitationDelivery.State.REVIEW,Instant.now(),PageRequest.of(0,batchSize));
        for (Long id : ids) {
            if (reservations.reserve(id)) checks.check(id);
        }
    }
}
