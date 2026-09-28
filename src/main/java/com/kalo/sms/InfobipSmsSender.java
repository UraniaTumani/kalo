package com.kalo.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Infobip, over its SMS v3 API.
 *
 * Chosen for Albania rather than on general merit: Infobip is headquartered in
 * Croatia and generally has the better +355 routes and pricing of the global
 * providers. Swapping it for Twilio or a local aggregator is another class
 * implementing {@link SmsSender} and one more branch in {@link SmsConfig} —
 * nothing outside this package knows which company carries the message.
 *
 * Three things about this class are deliberate and worth not undoing.
 *
 * It never logs the message. The body carries a live one-time code, and a code
 * in a log file is a code in every aggregator, terminal scrollback and pasted
 * support ticket it reaches afterwards. The phone number is logged in full only
 * on a failure, where knowing which number did not receive anything is the
 * whole point of the line.
 *
 * It never throws. {@link SmsSender} forbids it: the send runs on a background
 * thread after the HTTP response has already gone out, so there is nobody left
 * to tell and an exception would only put a stack trace on a pool thread. A
 * failure is logged at ERROR and swallowed; the code stays valid for its five
 * minutes, so a resend from the page is the recovery.
 *
 * It does not parse the response body. Infobip answers 200 with a per-message
 * status, but a 2xx means *accepted for delivery*, not delivered — the actual
 * outcome arrives later through delivery reports, which is a separate feature
 * this does not implement. Reading fields out of the body would imply a
 * guarantee the call cannot make, and would couple this class to a response
 * shape it has no other reason to depend on.
 */
@Slf4j
public class InfobipSmsSender implements SmsSender {

    /**
     * SMS v3. The older /sms/2/text/advanced still works and carries options
     * this does not need — validity periods, transliteration, scheduling — so
     * v3 is the smaller surface for sending one message to one number.
     */
    private static final String SEND_PATH = "/sms/3/messages";

    private final RestClient client;
    private final String sender;

    public InfobipSmsSender(RestClient client, String sender) {
        this.client = client;
        this.sender = sender;
    }

    @Override
    public void send(String phone, String message) {

        String destination = toInfobipNumber(phone);

        try {
            client.post()
                    .uri(SEND_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload(destination, message))
                    .retrieve()
                    .toBodilessEntity();

            /*
             * "Accepted", not "sent". Infobip has taken the message; whether it
             * reaches a handset depends on the route and the operator, and this
             * call cannot know. Overstating it here would be the same mistake
             * the dispatcher's wording avoids.
             */
            log.info("SMS accepted by Infobip for delivery: to={}", masked(phone));

        } catch (RestClientResponseException refused) {
            /*
             * The body is logged because Infobip's errors are the useful kind —
             * an unregistered sender ID, an exhausted balance, a blocked
             * destination — and none of them contains the message text. The
             * request body, which does, is never logged.
             */
            log.error(
                    "Infobip refused a recovery code: to={} status={} body={}",
                    phone,
                    refused.getStatusCode(),
                    refused.getResponseBodyAsString()
            );
        } catch (RuntimeException failed) {
            /* Timeout, DNS, TLS: no response to quote. */
            log.error("Infobip could not be reached for a recovery code: to={}", phone, failed);
        }
    }

    /**
     * The request body, exactly the shape Infobip's SMS v3 documents.
     *
     * Hand-built from maps rather than a DTO because it is written once, read
     * once, and never returned to a caller — a record per level of nesting
     * would be three classes to describe four fields.
     */
    private Map<String, Object> payload(String destination, String message) {

        return Map.of(
                "messages", List.of(Map.of(
                        "sender", sender,
                        "destinations", List.of(Map.of("to", destination)),
                        "content", Map.of("text", message)
                ))
        );
    }

    /**
     * Infobip wants an international number without the plus.
     *
     * Everything inside KALO stores E.164 with the leading "+", because that is
     * what PhoneNumberNormalizer produces and what every other part of the
     * system compares against. Stripping it here rather than anywhere else
     * keeps that one provider's spelling inside that one provider's class.
     */
    private static String toInfobipNumber(String phone) {
        return phone.startsWith("+") ? phone.substring(1) : phone;
    }

    /**
     * Enough of the number to find the account, not enough to be a contact list.
     *
     * The success path runs for every recovery, so it is the line that would
     * accumulate; the failure paths log the number in full, because a support
     * question about one undelivered message needs to name it.
     */
    private static String masked(String phone) {

        if (phone.length() <= 4) {
            return "***";
        }

        return "***" + phone.substring(phone.length() - 4);
    }
}
