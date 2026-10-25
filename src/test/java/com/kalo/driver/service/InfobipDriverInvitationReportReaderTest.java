package com.kalo.driver.service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class InfobipDriverInvitationReportReaderTest {
    private static final String BASE = "https://example.api.infobip.com";
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String PHONE = "+355690000001";
    private static final String URL = BASE + "/sms/3/logs?messageId=" + ID + "&limit=2";
    private MockRestServiceServer server;
    private InfobipDriverInvitationReportReader reader;
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl(BASE).defaultHeader(HttpHeaders.AUTHORIZATION,"App test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        reader = new InfobipDriverInvitationReportReader(builder.build(),"MRTAXI");
    }
    @AfterEach void verify() { server.verify(); }
    private String row(int group,int id) {
        return "{\"messageId\":\"" + ID + "\",\"destination\":\"355690000001\",\"sender\":\"MRTAXI\",\"status\":{\"groupId\":"
                + group + ",\"id\":" + id + "},\"content\":{\"text\":\"sensitive token must be ignored\"}}";
    }
    private String body(int group,int id) { return "{\"results\":[" + row(group,id) + "]}"; }
    private void responds(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    private DriverInvitationReportReader.Evidence read() { return reader.lookup(ID,PHONE); }
    private void reset() { server.verify(); server.reset(); }
    @Test void usesAuthenticatedReadOnlyLogsAndMatchesAcceptance() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION,"App test-key"))
                .andRespond(withSuccess(body(3,5),MediaType.APPLICATION_JSON));
        assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.ACCEPTED);
    }
    @Test void repeatedReadsDoNotConsumeReport() {
        responds(body(3,5)); responds(body(3,5));
        assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.ACCEPTED);
        assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.ACCEPTED);
    }
    @Test void explicitFinalFailureGroupsAreRejected() {
        for (int group : new int[]{2,4,5}) {
            responds(body(group,11)); assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.REJECTED); reset();
        }
    }
    @Test void recognizedPendingStatusConfirmsProviderAcceptanceOnly() {
        for (int status : new int[]{3,7,26}) {
            responds(body(1,status)); assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.ACCEPTED); reset();
        }
    }
    @Test void absentMalformedUnknownOrPaginatedDataRemainUnknown() {
        for (String body : new String[]{"", "{}", "{bad", "{\"results\":[]}", body(1,999),
                "{\"results\":["+row(3,5)+"],\"cursor\":{\"nextCursor\":\"next\"}}"}) {
            responds(body); assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN); reset();
        }
    }
    @Test void mismatchedMessageDestinationOrSenderCannotBeEvidence() {
        for (String body : new String[]{body(3,5).replace(ID,"wrong"), body(3,5).replace("355690000001","355690000002"),
                body(3,5).replace("MRTAXI","OTHER")}) {
            responds(body); assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN); reset();
        }
    }
    @Test void multipleMatchesCannotResolveAmbiguity() {
        responds("{\"results\":["+row(3,5)+","+row(5,11)+"]}");
        assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN);
    }
    @Test void duplicateRejectionDoesNotProveOriginalSendFailedOrSucceeded() {
        responds(body(5,23)); assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN);
    }
    @Test void httpErrorsKeepOutcomeUnknown() {
        for (int code : new int[]{401,403,404,429,500}) {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(code)));
            assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN); reset();
        }
    }
    @Test void networkFailureDoesNotExposeProviderException() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("sensitive response")));
        assertThat(read()).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN);
    }
    @Test void invalidCorrelationParametersNeverCallProvider() {
        assertThat(reader.lookup("invalid",PHONE)).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN);
        assertThat(reader.lookup(ID,"invalid")).isEqualTo(DriverInvitationReportReader.Evidence.UNKNOWN);
    }
    @Test void configurationRejectsUnsafeHostsAndMissingSecrets() {
        for (String base : new String[]{"http://example.api.infobip.com", "https://attacker.test", "https://example.api.infobip.com:8443",
                "https://user:secret@example.api.infobip.com", "https://example.api.infobip.com?key=secret"}) {
            assertThatThrownBy(() -> InfobipDriverInvitationReportReader.create(base,"secret","MRTAXI"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid invitation report-reader configuration");
        }
        assertThatThrownBy(() -> InfobipDriverInvitationReportReader.create(BASE,"","MRTAXI"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
