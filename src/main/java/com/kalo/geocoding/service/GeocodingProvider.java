package com.kalo.geocoding.service;

import com.kalo.geocoding.dto.PlaceResponse;

import java.util.List;

/**
 * Turns text into coordinates, and coordinates back into text.
 *
 * This interface used to live in the browser. Moving it here is what lets a
 * paid provider's key stay on the server, and makes the traffic ours to shape —
 * the address field fires on every pause in typing, which is the pattern
 * Nominatim's usage policy explicitly asks people not to send it.
 *
 * Swapping provider is writing one more class with this shape and changing
 * {@code app.geocoding.provider}. No controller, service or page changes.
 */
public interface GeocodingProvider {

    /**
     * Places matching free text, best first. An empty list is a normal answer;
     * a provider that is unreachable throws.
     */
    List<PlaceResponse> search(String query);

    /** A human-readable address for a point, or null when there is none. */
    String reverse(double latitude, double longitude);

    /** The value of {@code app.geocoding.provider} this implementation answers to. */
    String name();
}
