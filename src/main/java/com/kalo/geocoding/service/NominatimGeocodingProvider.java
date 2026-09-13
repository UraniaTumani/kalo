package com.kalo.geocoding.service;

import com.kalo.geocoding.dto.PlaceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * OpenStreetMap's Nominatim.
 *
 * Kept as the default because it needs no key and no billing account, which is
 * what makes a demo possible. It is not a production answer: the usage policy
 * caps traffic at roughly one request per second, offers no SLA, and asks people
 * not to use it for exactly this — autocomplete as somebody types.
 *
 * Running it from here rather than from the browser is still worth doing. It
 * gives the requests a real User-Agent, which the policy requires and a browser
 * cannot set; it lets one cached answer serve everybody instead of every visitor
 * asking again; and it means replacing this class is the whole migration.
 */
@Slf4j
public class NominatimGeocodingProvider implements GeocodingProvider {

    /** KALO does not operate outside Albania yet, so results are biased to it. */
    private static final String COUNTRY_CODES = "al";

    private static final int MAX_RESULTS = 6;

    private final RestClient client;
    private final String userAgent;

    public NominatimGeocodingProvider(RestClient client, String userAgent) {
        this.client = client;
        this.userAgent = userAgent;
    }

    @Override
    public String name() {
        return "nominatim";
    }

    @Override
    public List<PlaceResponse> search(String query) {

        List<Map<String, Object>> results = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", query)
                        .queryParam("format", "jsonv2")
                        .queryParam("limit", MAX_RESULTS)
                        .queryParam("countrycodes", COUNTRY_CODES)
                        .queryParam("addressdetails", 0)
                        .build())
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });

        if (results == null) {
            return List.of();
        }

        return results.stream().map(NominatimGeocodingProvider::toPlace).toList();
    }

    @Override
    public String reverse(double latitude, double longitude) {

        Map<String, Object> result = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/reverse")
                        .queryParam("lat", latitude)
                        .queryParam("lon", longitude)
                        .queryParam("format", "jsonv2")
                        .queryParam("addressdetails", 0)
                        .build())
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });

        if (result == null) {
            return null;
        }

        Object displayName = result.get("display_name");

        return displayName == null ? null : displayName.toString();
    }

    private static PlaceResponse toPlace(Map<String, Object> result) {

        String displayName = String.valueOf(result.getOrDefault("display_name", ""));
        Object name = result.get("name");

        /*
         * Nominatim's `name` is often absent for a plain street result, in which
         * case the first comma-separated part of the full address is the closest
         * thing to a short label.
         */
        String label = name != null && !name.toString().isBlank()
                ? name.toString().trim()
                : displayName.split(",")[0].trim();

        return new PlaceResponse(
                String.valueOf(result.get("place_id")),
                label,
                displayName,
                Double.parseDouble(String.valueOf(result.get("lat"))),
                Double.parseDouble(String.valueOf(result.get("lon")))
        );
    }
}
