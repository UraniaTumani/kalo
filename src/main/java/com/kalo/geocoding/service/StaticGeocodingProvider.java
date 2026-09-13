package com.kalo.geocoding.service;

import com.kalo.geocoding.dto.PlaceResponse;

import java.util.List;
import java.util.Locale;

/**
 * A fixed set of Tirana landmarks, for tests and for the browser suite.
 *
 * This exists so automated runs exercise the real endpoint — the real
 * controller, the real cache, the real validation — without sending a third
 * party a request every time somebody types. Stubbing the HTTP call in the
 * browser tests would have tested less and hidden more.
 *
 * Selected with {@code app.geocoding.provider=static}. Never the default.
 */
public class StaticGeocodingProvider implements GeocodingProvider {

    private static final List<PlaceResponse> PLACES = List.of(
            new PlaceResponse(
                    "static-1",
                    "Rruga e Kavajes",
                    "Rruga e Kavajes, Tirane, Albania",
                    41.3275,
                    19.8187
            ),
            new PlaceResponse(
                    "static-2",
                    "Sheshi Skenderbej",
                    "Sheshi Skenderbej, Tirane, Albania",
                    41.3320,
                    19.8230
            ),
            new PlaceResponse(
                    "static-3",
                    "Blloku",
                    "Blloku, Tirane, Albania",
                    41.3210,
                    19.8170
            )
    );

    @Override
    public String name() {
        return "static";
    }

    @Override
    public List<PlaceResponse> search(String query) {

        String needle = query.toLowerCase(Locale.ROOT).trim();

        List<PlaceResponse> matched = PLACES.stream()
                .filter(place -> place.label().toLowerCase(Locale.ROOT).contains(needle))
                .toList();

        /*
         * Falling back to everything keeps a test that types something arbitrary
         * from getting an empty list and failing for the wrong reason.
         */
        return matched.isEmpty() ? PLACES : matched;
    }

    @Override
    public String reverse(double latitude, double longitude) {

        return PLACES.stream()
                .min((a, b) -> Double.compare(
                        distanceSquared(a, latitude, longitude),
                        distanceSquared(b, latitude, longitude)
                ))
                .map(PlaceResponse::description)
                .orElse(null);
    }

    /** Squared degrees: good enough to pick a nearest point, and cheap. */
    private static double distanceSquared(PlaceResponse place, double latitude, double longitude) {
        double dLat = place.latitude() - latitude;
        double dLng = place.longitude() - longitude;
        return dLat * dLat + dLng * dLng;
    }
}
