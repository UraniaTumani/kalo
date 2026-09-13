package com.kalo.geocoding.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.geocoding.dto.PlaceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The application's own front door to geocoding.
 *
 * Two jobs beyond calling the provider. It caches, because an address field
 * fires on every pause in typing and a city's worth of people search the same
 * few streets — one answer can serve all of them. And it fails soft: a provider
 * being unreachable turns the suggestion list grey, it does not break booking,
 * which still works from a map tap.
 *
 * The cache is a bounded map rather than a cache library: entries are small, the
 * eviction policy is "old ones go", and adding a dependency for that would be a
 * version to keep in step for no benefit.
 */
@Slf4j
@Service
public class GeocodingService {

    /** Shorter than the data changes, longer than a person's typing pause. */
    private static final Duration TTL = Duration.ofHours(6);

    /** Enough for a country's worth of common searches, small enough to forget. */
    private static final int MAX_ENTRIES = 2_000;

    private static final int MIN_QUERY_LENGTH = 3;

    private final GeocodingProvider provider;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Value("${app.geocoding.cache-enabled:true}")
    private boolean cacheEnabled;

    public GeocodingService(GeocodingProvider provider) {
        this.provider = provider;
        log.info("Geocoding provider: {}", provider.name());
    }

    public List<PlaceResponse> search(String query) {

        String trimmed = query == null ? "" : query.trim();

        if (trimmed.length() < MIN_QUERY_LENGTH) {
            /*
             * Refused rather than forwarded: two letters match half of Tirana, so
             * the provider would be asked a question whose answer is useless.
             */
            throw new InvalidOperationException(
                    "Search for at least " + MIN_QUERY_LENGTH + " characters"
            );
        }

        String key = "s:" + trimmed.toLowerCase(Locale.ROOT);

        List<PlaceResponse> cached = cachedPlaces(key);
        if (cached != null) {
            return cached;
        }

        try {

            List<PlaceResponse> places = provider.search(trimmed);
            put(key, places, null);
            return places;

        } catch (Exception exception) {

            /*
             * Logged without the query: what somebody is typing into a pickup
             * field is where they are, and that does not belong in a log.
             */
            log.warn("Geocoding search failed via {}: {}", provider.name(),
                    exception.getClass().getSimpleName());

            return List.of();
        }
    }

    public String reverse(double latitude, double longitude) {

        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new InvalidOperationException("Coordinates are out of range");
        }

        /*
         * Rounded to about eleven metres. Two taps on the same doorway should be
         * one cache entry, not two.
         */
        String key = "r:%.4f,%.4f".formatted(latitude, longitude);

        Entry entry = cache.get(key);
        if (cacheEnabled && entry != null && entry.isFresh()) {
            return entry.address();
        }

        try {

            String address = provider.reverse(latitude, longitude);
            put(key, null, address);
            return address;

        } catch (Exception exception) {

            log.warn("Reverse geocoding failed via {}: {}", provider.name(),
                    exception.getClass().getSimpleName());

            return null;
        }
    }

    private List<PlaceResponse> cachedPlaces(String key) {

        if (!cacheEnabled) {
            return null;
        }

        Entry entry = cache.get(key);

        return entry != null && entry.isFresh() ? entry.places() : null;
    }

    private void put(String key, List<PlaceResponse> places, String address) {

        if (!cacheEnabled) {
            return;
        }

        /*
         * Cleared rather than evicted one by one. It refills from the next few
         * searches, and tracking access order would cost more than it saves for
         * something this small.
         */
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }

        cache.put(key, new Entry(places, address, Instant.now()));
    }

    private record Entry(List<PlaceResponse> places, String address, Instant storedAt) {

        boolean isFresh() {
            return storedAt.isAfter(Instant.now().minus(TTL));
        }
    }
}
