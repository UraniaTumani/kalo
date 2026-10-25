package com.kalo.driver.service;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
/** Short reservation transaction, committed before provider HTTP. */
@Service
public class DriverInvitationReportReservation {
    private final DriverInvitationDeliveryRepository deliveries;
    public DriverInvitationReportReservation(DriverInvitationDeliveryRepository deliveries) { this.deliveries = deliveries; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(Long id) {
        return deliveries.findForUpdateById(id).map(row -> row.reserveReportCheck(Instant.now())).orElse(false);
    }
}
