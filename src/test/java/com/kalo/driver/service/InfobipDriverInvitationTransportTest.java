package com.kalo.driver.service;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class InfobipDriverInvitationTransportTest {
    private static final String BASE = "https://example.api.infobip.com";
    private static final String PHONE = "+355690000001";
    private static final String KEY = "driver-invitation-42";
    private final String token = new DriverInvitationTokenCodec().generate();
    private MockRestServiceServer server;
    private InfobipDriverInvitationTransport transport;
    @BeforeEach void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "App test-only-key");
        server = MockRestServiceServer.bindTo(builder).build();
        transport = new InfobipDriverInvitationTransport(builder.build(), "MRTAXI", "https://app.example.test/driver/activate");
    }
    @AfterEach void verifyServer() { server.verify(); }
    private String id() {
        String digest = new DriverInvitationTokenCodec().digest(token);
        return UUID.nameUUIDFromBytes(("kalo-driver-v1|" + KEY + "|" + PHONE.substring(1) + "|" + digest)
                .getBytes(StandardCharsets.US_ASCII)).toString();
    }
    private String response(int group, int status) {
        return "{\"messages\":[{\"messageId\":\"" + id() + "\",\"destination\":\"355690000001\",\"status\":{\"groupId\":"
                + group + ",\"id\":" + status + "}}]}";
    }
    private DriverInvitationTransport.Outcome send() { return transport.send(PHONE, token, KEY); }
    private void responds(String body) {
        server.expect(requestTo(BASE + "/sms/3/messages")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    private void reset() { server.verify(); server.reset(); }

    @Test void postsV3PayloadWithSecretInFragmentAndParsesAcceptance() {
        server.expect(requestTo(BASE + "/sms/3/messages"))
                .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "App test-only-key"))
                .andExpect(request -> {
                    String body = new String(((org.springframework.mock.http.client.MockClientHttpRequest)request)
                            .getBodyAsBytes(), StandardCharsets.UTF_8);
                    assertThat(JsonPath.<String>read(body, "$.messages[0].destinations[0].to")).isEqualTo("355690000001");
                    assertThat(JsonPath.<String>read(body, "$.messages[0].destinations[0].messageId")).isEqualTo(id());
                    assertThat(JsonPath.<String>read(body, "$.messages[0].sender")).isEqualTo("MRTAXI");
                    assertThat(JsonPath.<String>read(body, "$.messages[0].content.text"))
                            .isEqualTo("MR TAXI: Activate your driver account: https://app.example.test/driver/activate#invite=" + token);
                }).andRespond(withSuccess(response(1,26), MediaType.APPLICATION_JSON));
        assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.ACCEPTED);
    }
    @Test void retriesReuseSameProviderMessageId() {
        for (int n=0;n<2;n++) {
            server.expect(requestTo(BASE + "/sms/3/messages")).andExpect(request -> {
                String body = new String(((org.springframework.mock.http.client.MockClientHttpRequest)request)
                        .getBodyAsBytes(), StandardCharsets.UTF_8);
                assertThat(JsonPath.<String>read(body, "$.messages[0].destinations[0].messageId")).isEqualTo(id());
            }).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }
        assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.RETRYABLE_FAILURE);
        assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.RETRYABLE_FAILURE);
    }
    @Test void knownPendingStatusesCountAsProviderAcceptance() {
        for (int status : new int[]{3,7,26}) {
            responds(response(1,status)); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.ACCEPTED); reset();
        }
    }
    @Test void deliveredStatusesCountAsAcceptance() {
        for (int status : new int[]{2,5}) {
            responds(response(3,status)); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.ACCEPTED); reset();
        }
    }
    @Test void explicitRejectedExpiredAndUndeliverableArePermanent() {
        for (int group : new int[]{2,4,5}) {
            responds(response(group,11)); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.PERMANENT_FAILURE); reset();
        }
    }
    @Test void duplicateIdRejectionDoesNotClaimAcceptance() {
        responds(response(5,23)); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.REVIEW_REQUIRED);
    }
    @Test void providerSystemErrorIsRetryable() {
        responds(response(5,21)); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.RETRYABLE_FAILURE);
    }
    @Test void throttleIsRetryableButTimeoutAndServerErrorsRequireReview() {
        for (int code : new int[]{408,429,500,503}) {
            server.expect(requestTo(BASE + "/sms/3/messages")).andRespond(withStatus(HttpStatusCode.valueOf(code)));
            DriverInvitationTransport.Outcome expected = code == 429
                    ? DriverInvitationTransport.Outcome.RETRYABLE_FAILURE : DriverInvitationTransport.Outcome.REVIEW_REQUIRED;
            assertThat(send()).isEqualTo(expected); reset();
        }
    }
    @Test void badRequestAndAuthenticationFailureArePermanent() {
        for (int code : new int[]{400,401,403,404,422}) {
            server.expect(requestTo(BASE + "/sms/3/messages")).andRespond(withStatus(HttpStatusCode.valueOf(code)));
            assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.PERMANENT_FAILURE); reset();
        }
    }
    @Test void missingMalformedOrEmptySuccessResponseIsNotAcceptance() {
        for (String body : new String[]{"", "{bad", "{}", "{\"messages\":[]}", "{\"messages\":[{}]}"}) {
            responds(body); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.REVIEW_REQUIRED); reset();
        }
    }
    @Test void mismatchedMessageOrDestinationIsNotAcceptance() {
        for (String body : new String[]{response(1,26).replace(id(), "different"),
                response(1,26).replace("355690000001", "355690000002")}) {
            responds(body); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.REVIEW_REQUIRED); reset();
        }
    }
    @Test void unknownOrCancelledPendingStatusIsNotAcceptance() {
        for (int status : new int[]{98,999}) {
            responds(response(1,status)); assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.REVIEW_REQUIRED); reset();
        }
    }
    @Test void networkExceptionRequiresReview() {
        server.expect(requestTo(BASE + "/sms/3/messages")).andRespond(withException(new IOException("sensitive provider data")));
        assertThat(send()).isEqualTo(DriverInvitationTransport.Outcome.REVIEW_REQUIRED);
    }
    @Test void invalidDestinationTokenOrRequestKeyNeverSends() {
        assertThat(transport.send("invalid",token,KEY)).isEqualTo(DriverInvitationTransport.Outcome.PERMANENT_FAILURE);
        assertThat(transport.send(PHONE,"secret",KEY)).isEqualTo(DriverInvitationTransport.Outcome.PERMANENT_FAILURE);
        assertThat(transport.send(PHONE,token,"invalid")).isEqualTo(DriverInvitationTransport.Outcome.PERMANENT_FAILURE);
    }
    @Test void rejectsUnsafeActivationUrlsAndInvalidSender() {
        for (String page : new String[]{"http://app.example.test", "https://user:password@app.example.test", "https://app.example.test?token=secret", "https://app.example.test#fragment"}) {
            assertThatThrownBy(() -> new InfobipDriverInvitationTransport(RestClient.create(), "MRTAXI",page))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid invitation SMS configuration");
        }
        assertThatThrownBy(() -> new InfobipDriverInvitationTransport(RestClient.create(), "INVALID SENDER", "https://app.example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesCredentialsToUntrustedProviderHostAndMissingKey() {
        for (String base : new String[]{"http://example.api.infobip.com", "https://attacker.example.test", "https://example.api.infobip.com/path", "https://example.api.infobip.com:8443"}) {
            assertThatThrownBy(() -> InfobipDriverInvitationTransport.create(base,"secret","MRTAXI","https://app.example.test"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid invitation SMS configuration");
        }
        assertThatThrownBy(() -> InfobipDriverInvitationTransport.create(BASE,"","MRTAXI","https://app.example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
