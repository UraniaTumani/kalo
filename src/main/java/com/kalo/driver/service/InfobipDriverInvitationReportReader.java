package com.kalo.driver.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Unwired GET /sms/3/logs adapter. Never consumes /reports or logs provider bodies.
 * Log records may contain the original secret message: discard all content fields.
 * Empty/multiple/malformed/mismatched results are UNKNOWN, never proof of failure.
 * Infobip log retention is limited; absent older records require separate review.
 */
public final class InfobipDriverInvitationReportReader implements DriverInvitationReportReader {
    private final RestClient client;
    private final String sender;
    InfobipDriverInvitationReportReader(RestClient client, String sender) {
        if (client == null || sender == null || !sender.matches("[A-Za-z0-9]{1,11}")) {
            throw invalidConfiguration();
        }
        this.client = client; this.sender = sender;
    }
    public static InfobipDriverInvitationReportReader create(String baseUrl, String apiKey, String sender) {
        URI base;
        try {
            base = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                    || !base.getHost().endsWith(".api.infobip.com") || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null
                    || (base.getPort() != -1 && base.getPort() != 443)
                    || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                    || apiKey == null || apiKey.isBlank() || apiKey.contains("\r") || apiKey.contains("\n")) {
                throw invalidConfiguration();
            }
        } catch (RuntimeException invalid) { throw invalidConfiguration(); }
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2)); requests.setReadTimeout(Duration.ofSeconds(5));
        var client = RestClient.builder().baseUrl(base.toASCIIString()).requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION,"App " + apiKey).build();
        return new InfobipDriverInvitationReportReader(client,sender);
    }
    @Override
    public Evidence lookup(String providerMessageId, String destination) {
        if (providerMessageId == null || !providerMessageId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || destination == null || !destination.matches("\\+[1-9][0-9]{6,14}")) return Evidence.UNKNOWN;
        try {
            Map<?,?> response = client.get().uri(uri -> uri.path("/sms/3/logs")
                    .queryParam("messageId",providerMessageId).queryParam("limit",2).build())
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(Map.class);
            if (response == null || response.get("cursor") != null
                    || !(response.get("results") instanceof List<?> results) || results.size() != 1
                    || !(results.get(0) instanceof Map<?,?> result)
                    || !providerMessageId.equals(result.get("messageId"))
                    || !destination.substring(1).equals(result.get("destination"))
                    || !sender.equals(result.get("sender"))
                    || !(result.get("status") instanceof Map<?,?> status)
                    || !(status.get("groupId") instanceof Integer group)
                    || !(status.get("id") instanceof Integer id)) return Evidence.UNKNOWN;
            if ((group == 1 && (id == 3 || id == 7 || id == 26))
                    || (group == 3 && (id == 2 || id == 5))) return Evidence.ACCEPTED;
            // Duplicate rejection describes a later attempt, not the original send.
            if (group == 5 && id == 23) return Evidence.UNKNOWN;
            if (group == 2 || group == 4 || group == 5) return Evidence.REJECTED;
            return Evidence.UNKNOWN;
        } catch (RuntimeException uncertain) {
            return Evidence.UNKNOWN; // No provider exception or secret response is propagated.
        }
    }
    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("Invalid invitation report-reader configuration");
    }
}
