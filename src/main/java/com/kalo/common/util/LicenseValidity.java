package com.kalo.common.util;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One answer to "is this licence still valid today", used by everything that
 * has to decide it.
 *
 * Three rules live here rather than being restated at each call site, because
 * every one of them is a business decision somebody could reasonably get wrong
 * the second time they wrote it:
 *
 * <ol>
 *   <li><b>Europe/Tirane, not the server's default zone.</b> A licence is a
 *   document issued in a country, and whether it has expired is a question
 *   about the date in that country. A container running UTC would otherwise
 *   treat a licence as expired for the last hour or two of its final day in
 *   summer, and the operator would have no idea why their driver vanished.</li>
 *
 *   <li><b>Valid through the expiry date, invalid from the day after.</b> A
 *   licence reading 31 December is good all of 31 December. This is the reading
 *   a driver holding the document would expect, and it matches the existing
 *   write-time check in DriverServiceImpl, which rejects a date already in the
 *   past rather than one that is merely today.</li>
 *
 *   <li><b>A missing date is not valid.</b> taxi_companies.license_expiry_date
 *   is nullable and UpdatePartnerProfileRequest does not require it, so a null
 *   is reachable through an ordinary profile edit. Treating null as "fine"
 *   would make the whole check bypassable by clearing the field — an expired
 *   company could delete its date and keep trading. Drivers cannot reach this
 *   case at all: their column is NOT NULL and both driver DTOs require it.</li>
 * </ol>
 *
 * Static because it holds no state and because the date it reads is the same
 * for every caller. Tests control the data rather than the clock: an expiring
 * licence is expressed as a row dated {@link #today()}, not by freezing time.
 */
public final class LicenseValidity {

    /**
     * Where "today" is decided.
     *
     * The pilot operates in Albania. If this product ever serves a second
     * country, this constant is the thing that has to become per-company rather
     * than global — and the fact that it is one constant is what will make that
     * change findable.
     */
    public static final ZoneId ZONE = ZoneId.of("Europe/Tirane");

    private LicenseValidity() {
    }

    /** Today's date in the zone licences are judged in. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * Whether a licence with this expiry date may be relied on right now.
     *
     * @param expiryDate the licence's expiry date, or null if none is recorded
     */
    public static boolean isValid(LocalDate expiryDate) {
        return isValidOn(expiryDate, today());
    }

    /**
     * Whether a licence with this expiry date may be relied on for a given day.
     *
     * Separated from {@link #isValid} so a caller that has already established
     * "today" — the taxi search, which must hand the same date to the database
     * rather than ask twice — can reuse it, and so a test can state the day it
     * means instead of implying it.
     */
    public static boolean isValidOn(LocalDate expiryDate, LocalDate day) {

        if (expiryDate == null) {
            return false;
        }

        return !expiryDate.isBefore(day);
    }
}
