package com.kalo.driver.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Unwired invitation-only adapter: no Spring annotation or configuration enables it.
 * Never logs request/response bodies, destination, credentials or exceptions.
 * The stable provider messageId aids correlation/duplicate detection, not an
 * exactly-once promise. Duplicate rejection requires reconciliation, not acceptance.
 * The future activation page must read and clear #invite= before loading analytics.
 */
public final class InfobipDriverInvitationTransport implements DriverInvitationTransport {
    private final RestClient client;
    private final String sender;
    private final String activationPage;
    private final DriverInvitationTokenCodec codec = new DriverInvitationTokenCodec();

    /** Package-private HTTP seam for mocked-provider tests. */
    InfobipDriverInvitationTransport(RestClient client, String sender, String activationPage) {
        URI page = requireHttps(activationPage);
        if (client == null || sender == null || !sender.matches("[A-Za-z0-9]{1,11}")) {
            throw new IllegalArgumentException("Invalid invitation SMS configuration");
        }
        this.client = client;
        this.sender = sender;
        this.activationPage = page.toASCIIString();
    }

    /** Future wiring may call this only with externally managed provider secrets. */
    public static InfobipDriverInvitationTransport create(String baseUrl, String apiKey,
            String sender, String activationPage) {
        URI base = requireHttps(baseUrl);
        if (!base.getHost().endsWith(".api.infobip.com")
                || (base.getPort() != -1 && base.getPort() != 443)
                || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                || apiKey == null || apiKey.isBlank() || apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new IllegalArgumentException("Invalid invitation SMS configuration");
        }
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(5));
        RestClient client = RestClient.builder().baseUrl(base.toASCIIString())
                .requestFactory(requests).defaultHeader(HttpHeaders.AUTHORIZATION, "App " + apiKey).build();
        return new InfobipDriverInvitationTransport(client, sender, activationPage);
    }

    @Override
    public Outcome send(String destination, String token, String idempotencyKey) {
        if (destination == null || !destination.matches("\\+[1-9][0-9]{6,14}")
                || idempotencyKey == null || !idempotencyKey.matches("driver-invitation-[1-9][0-9]*")) {
            return Outcome.PERMANENT_FAILURE;
        }
        String digest;
        try { digest = codec.digest(token); }
        catch (IllegalArgumentException invalid) { return Outcome.PERMANENT_FAILURE; }
        String phone = destination.substring(1);
        // Include token digest to avoid collisions across environments/database restores.
        String messageId = DriverInvitationMessageId.create(destination, digest, idempotencyKey);
        String text = "MR TAXI: Activate your driver account: " + activationPage + "#invite=" + token;
        var payload = Map.of("messages", List.of(Map.of("sender", sender,
                "destinations", List.of(Map.of("to", phone, "messageId", messageId)),
                "content", Map.of("text", text))));
        try {
            Map<?, ?> response = client.post().uri("/sms/3/messages")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(Map.class);
            return classify(response, phone, messageId);
        } catch (RestClientResponseException refused) {
            int code = refused.getStatusCode().value();
            if (code == 429) return Outcome.RETRYABLE_FAILURE;
            if (code == 408 || code >= 500) return Outcome.REVIEW_REQUIRED;
            return Outcome.PERMANENT_FAILURE;
        } catch (RuntimeException uncertain) {
            // DNS, timeout, malformed response: never expose provider exception content.
            return Outcome.REVIEW_REQUIRED;
        }
    }

    private static Outcome classify(Map<?, ?> response, String phone, String messageId) {
        if (response == null || !(response.get("messages") instanceof List<?> messages)
                || messages.size() != 1 || !(messages.get(0) instanceof Map<?, ?> message)
                || !messageId.equals(message.get("messageId"))
                || !phone.equals(message.get("destination"))
                || !(message.get("status") instanceof Map<?, ?> status)
                || !(status.get("groupId") instanceof Integer group)
                || !(status.get("id") instanceof Integer id)) {
            return Outcome.REVIEW_REQUIRED;
        }
        if ((group == 1 && (id == 3 || id == 7 || id == 26))
                || (group == 3 && (id == 2 || id == 5))) return Outcome.ACCEPTED;
        if (group == 5 && id == 23) return Outcome.REVIEW_REQUIRED;
        if (group == 5 && id == 21) return Outcome.RETRYABLE_FAILURE;
        if (group == 2 || group == 4 || group == 5) return Outcome.PERMANENT_FAILURE;
        return Outcome.REVIEW_REQUIRED;
    }

    private static URI requireHttps(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid invitation SMS configuration");
        }
    }
}
