package com.kalo.partner.dto;

import java.time.Instant;
import java.util.List;

/**
 * Operational checklist for a company, independent of any passenger pickup.
 * A green result is not a promise that a taxi will match a particular location.
 */
public record PartnerReadinessResponse(
        boolean readyNow,
        Instant checkedAt,
        List<String> blockers
) {
}
