package com.kalo.driver.service;
import com.kalo.driver.entity.*;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverInvitationDeliveryRepository;
import com.kalo.driver.repository.DriverInvitationRepository;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class DriverInvitationReportCheckIntegrationTest extends AbstractIntegrationTest {
    @Autowired DriverInvitationDeliveryRepository deliveries;
    @Autowired DriverInvitationRepository invitations;
    @Autowired DriverInvitationReconciliation reconciliation;
    @Autowired DriverInvitationReportCheck disabledCheck;
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private String phone;
    private Long review() {
        var company = fixtures.approvedCompany();
        var driver = fixtures.driver(company,DriverStatus.ACTIVE,DriverAvailabilityStatus.OFFLINE); phone = driver.getPhone();
        var invitation = new DriverInvitation(); invitation.setDriver(driver); invitation.setCompany(company);
        invitation.setTokenHash("a".repeat(64)); invitation.setExpiresAt(Instant.now().plusSeconds(3600));
        invitations.saveAndFlush(invitation); Instant now = Instant.now();
        var row = new DriverInvitationDelivery(invitation,phone,"test-key","encrypted",now);
        row.recordProviderMessageId(ID); row.requireReview(now); return deliveries.saveAndFlush(row).getId();
    }
    @SuppressWarnings("unchecked") private DriverInvitationReportCheck check(DriverInvitationReportReader reader) {
        ObjectProvider<DriverInvitationReportReader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(reader);
        return new DriverInvitationReportCheck(deliveries,reconciliation,provider,true);
    }
    @Test void correlatedHttpLogResolvesReviewAndRepeatedCheckDoesNotRefetch() {
        Long id = review(); var builder = RestClient.builder().baseUrl("https://example.api.infobip.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.api.infobip.com/sms/3/logs?messageId="+ID+"&limit=2"))
                .andRespond(withSuccess("{\"results\":[{\"messageId\":\""+ID+"\",\"destination\":\""+phone.substring(1)
                        +"\",\"sender\":\"MRTAXI\",\"status\":{\"groupId\":3,\"id\":5}}]}",MediaType.APPLICATION_JSON));
        var checker = check(new InfobipDriverInvitationReportReader(builder.build(),"MRTAXI"));
        assertThat(checker.check(id)).isEqualTo(DriverInvitationReportCheck.Result.RESOLVED);
        assertThat(checker.check(id)).isEqualTo(DriverInvitationReportCheck.Result.SKIPPED);
        assertThat(deliveries.findById(id).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.ACCEPTED);
        server.verify();
    }
    @Test void absentProviderEvidenceLeavesDurableReviewUntouched() {
        Long id = review(); var reader = mock(DriverInvitationReportReader.class);
        when(reader.lookup(ID,phone)).thenReturn(DriverInvitationReportReader.Evidence.UNKNOWN);
        assertThat(check(reader).check(id)).isEqualTo(DriverInvitationReportCheck.Result.UNRESOLVED);
        var loaded = deliveries.findById(id).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
        assertThat(loaded.getAttempts()).isEqualTo(1); assertThat(loaded.getEncryptedPayload()).isNull();
    }
    @Test void actualApplicationReportCheckerIsDisabledByDefault() {
        Long id = review(); assertThat(disabledCheck.check(id)).isEqualTo(DriverInvitationReportCheck.Result.DISABLED);
        assertThat(deliveries.findById(id).orElseThrow().getState()).isEqualTo(DriverInvitationDelivery.State.REVIEW);
    }
}
