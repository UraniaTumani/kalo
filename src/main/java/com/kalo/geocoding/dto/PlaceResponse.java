package com.kalo.geocoding.dto;

/**
 * One place, in the only shape the booking page needs.
 *
 * Deliberately narrow: a provider's own response carries licence strings,
 * bounding boxes and address components that no screen reads, and passing them
 * through would make the provider visible to the client and harder to change.
 *
 * @param id          stable within one provider; used as a list key
 * @param label       short name, e.g. "Sheshi Skenderbej"
 * @param description full address, for telling two similar results apart
 */
public record PlaceResponse(
        String id,
        String label,
        String description,
        double latitude,
        double longitude
) {
}
