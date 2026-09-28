package com.kalo.sms;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Infobip sender, against a stubbed provider.
 *
 * Nothing here talks to Infobip. What is being tested is the part that is ours
 * and that nobody would notice was wrong until a real recovery failed: the
 * exact request shape, the authorization scheme, and the promise that a
 * provider having a bad day cannot take a thread down with it.
 *
 * The wire format is asserted field by field rather than by comparing a blob,
 * because that is the thing a future refactor can quietly break — Infobip
 * answers 200 to a well-formed request whether or not it contains a sender, and
 * the failure shows up as messages nobody receives.
 */
@DisplayName("Infobip SMS sender")
class InfobipSmsSenderTest {

    private static final String BASE_URL = "https://example.api.infobip.com";
    private static final String API_KEY = "test-key-not-a-real-credential";
    private static final String SENDER = "MRTAXI";

    private static final String PHONE = "+355690000001";
    private static final String MESSAGE = "MR TAXI: kodi 428913. Skadon pas 5 min.";

    private MockRestServiceServer server;
    private InfobipSmsSender sender;

    @BeforeEach
    void setUp() {

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "App " + API_KEY);

        server = MockRestServiceServer.bindTo(builder).build();
        sender = new InfobipSmsSender(builder.build(), SENDER);
    }

    @Test
    @DisplayName("it posts the documented SMS v3 request")
    void itPostsTheDocumentedRequest() {

        server.expect(requestTo(BASE_URL + "/sms/3/messages"))
                .andExpect(method(HttpMethod.POST))
                /*
                 * "App", not "Bearer". Infobip's own scheme, and getting it
                 * wrong is silent: the response is simply unauthorised, with
                 * nothing to say the scheme was the problem.
                 */
                .andExpect(header(HttpHeaders.AUTHORIZATION, "App " + API_KEY))
                .andExpect(request -> {

                    String body = new String(
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                    .getBodyAsBytes(),
                            StandardCharsets.UTF_8
                    );

                    assertThat(JsonPath.<String>read(body, "$.messages[0].sender"))
                            .isEqualTo(SENDER);

                    /*
                     * Without the plus. KALO stores E.164 with it, because that
                     * is what PhoneNumberNormalizer produces and what the rest
                     * of the system compares against; Infobip wants it gone.
                     * That conversion lives in this one class, so this is where
                     * it is pinned.
                     */
                    assertThat(JsonPath.<String>read(body, "$.messages[0].destinations[0].to"))
                            .isEqualTo("355690000001");

                    assertThat(JsonPath.<String>read(body, "$.messages[0].content.text"))
                            .isEqualTo(MESSAGE);
                })
                .andRespond(withSuccess("""
                        {"messages":[{"messageId":"abc","status":{"groupName":"PENDING"}}]}
                        """, MediaType.APPLICATION_JSON));

        sender.send(PHONE, MESSAGE);

        server.verify();
    }

    @Test
    @DisplayName("a refusal from Infobip does not escape the sender")
    void aRefusalIsSwallowed() {

        server.expect(requestTo(BASE_URL + "/sms/3/messages"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("""
                                {"requestError":{"serviceException":{"messageId":"UNAUTHORIZED"}}}
                                """)
                        .contentType(MediaType.APPLICATION_JSON));

        /*
         * The contract in SmsSender, and it is not politeness. This runs on a
         * background thread after the HTTP response has already gone out, so
         * there is nobody left to tell — an exception would put a stack trace
         * on a pool thread and change nothing a caller can see. An expired API
         * key or an unregistered sender ID must be a log line, not a crash.
         */
        assertThatCode(() -> sender.send(PHONE, MESSAGE)).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("a provider outage does not escape either")
    void anOutageIsSwallowed() {

        server.expect(requestTo(BASE_URL + "/sms/3/messages"))
                .andRespond(withServerError());

        assertThatCode(() -> sender.send(PHONE, MESSAGE)).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("a number already without a plus is sent unchanged")
    void aBareNumberIsLeftAlone() {

        server.expect(requestTo(BASE_URL + "/sms/3/messages"))
                .andExpect(request -> {

                    String body = new String(
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                    .getBodyAsBytes(),
                            StandardCharsets.UTF_8
                    );

                    /*
                     * Stripping a leading character is the kind of thing that
                     * becomes substring(1) applied unconditionally during a
                     * tidy-up, which would eat the first digit of every number
                     * that never had a plus.
                     */
                    assertThat(JsonPath.<String>read(body, "$.messages[0].destinations[0].to"))
                            .isEqualTo("355690000002");
                })
                .andRespond(withSuccess());

        sender.send("355690000002", MESSAGE);

        server.verify();
    }
}
