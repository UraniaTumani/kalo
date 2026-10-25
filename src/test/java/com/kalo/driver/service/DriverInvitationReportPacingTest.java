package com.kalo.driver.service;
import com.kalo.driver.entity.DriverInvitation;
import com.kalo.driver.entity.DriverInvitationDelivery;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
class DriverInvitationReportPacingTest {
    private final Instant now = Instant.parse("2026-10-25T10:00:00Z");
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private DriverInvitationDelivery review() {
        var invitation = new DriverInvitation(); invitation.setExpiresAt(now.plusSeconds(3600));
        var row = new DriverInvitationDelivery(invitation,"+355690000001","test-key","encrypted",now);
        row.setCreatedAt(now); row.recordProviderMessageId(ID); row.requireReview(now); return row;
    }
    @Test void firstLookupsBackOffAndEarlyReservationDoesNotSpendAttempt() {
        var row = review(); assertThat(row.reserveReportCheck(now)).isTrue();
        assertThat(row.getNextReportCheckAt()).isEqualTo(now.plusSeconds(300));
        assertThat(row.reserveReportCheck(now.plusSeconds(299))).isFalse(); assertThat(row.getReportChecks()).isEqualTo(1);
        assertThat(row.reserveReportCheck(now.plusSeconds(300))).isTrue();
        assertThat(row.getNextReportCheckAt()).isEqualTo(now.plusSeconds(900));
    }
    @Test void twelfthReservationStopsAutomaticChecksWithoutFailingInvitation() {
        var row = review(); Instant due = now;
        for (int n=0;n<12;n++) {
            assertThat(row.reserveReportCheck(due)).isTrue();
            if (n<11) { assertThat(row.getNextReportCheckAt()).isBeforeOrEqualTo(due.plusSeconds(21600)); due=row.getNextReportCheckAt(); }
        }
        assertThat(row.getReportChecks()).isEqualTo(12);
        assertThat(row.getReportStopReason()).isEqualTo(DriverInvitationDelivery.ReportStopReason.ATTEMPT_LIMIT);
        assertThat(row.getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
        assertThat(row.reserveReportCheck(due.plusSeconds(21600))).isFalse();
    }
    @Test void ageBoundaryStopsChecksAndKeepsReview() {
        var row = review(); assertThat(row.reserveReportCheck(now.plusSeconds(47*3600L))).isFalse();
        assertThat(row.getReportChecks()).isZero();
        assertThat(row.getReportStopReason()).isEqualTo(DriverInvitationDelivery.ReportStopReason.AGE_LIMIT);
        assertThat(row.getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
    }
    @Test void confirmedEvidenceClearsFutureSchedulingAndDoesNotGrantAnotherSend() {
        var row = review(); row.reserveReportCheck(now); row.reconcile(ID,true);
        assertThat(row.getNextReportCheckAt()).isNull(); assertThat(row.getReportStopReason()).isNull();
        assertThat(row.reserveReportCheck(now.plusSeconds(600))).isFalse(); assertThat(row.canAttempt(now)).isFalse();
    }
}
