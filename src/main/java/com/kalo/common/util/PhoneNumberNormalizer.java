package com.kalo.common.util;

/**
 * Puts Albanian phone numbers into one canonical form.
 *
 * A phone number is the login identifier and is unique per table, but
 * uniqueness is only as good as the spelling: without this, +355691234567,
 * 355691234567 and 0691234567 are the same person to a human and three
 * different rows to PostgreSQL. Normalising on the way in means the existing
 * unique constraints actually mean what they look like they mean.
 *
 * Numbers that are not recognisably Albanian are left alone apart from having
 * separators stripped, so an international number is neither mangled nor
 * rejected.
 */
public final class PhoneNumberNormalizer {

    private static final String ALBANIA_COUNTRY_CODE = "355";
    private static final String ALBANIA_PREFIX = "+" + ALBANIA_COUNTRY_CODE;

    private PhoneNumberNormalizer() {
    }

    /**
     * @param rawPhone whatever the caller typed, possibly with spaces, dashes,
     *                 dots or parentheses
     * @return the number in +355… form where it is recognisably Albanian,
     *         otherwise the same digits with separators removed; null and blank
     *         input come back unchanged so callers can validate them
     */
    public static String normalize(String rawPhone) {

        if (rawPhone == null) {
            return null;
        }

        String compact = rawPhone.replaceAll("[\\s()\\-.]", "");

        if (compact.isEmpty()) {
            return compact;
        }

        // 00 is the international prefix used across Europe; treat it as '+'.
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }

        if (compact.startsWith("+")) {
            return compact;
        }

        // A national number: the trunk '0' is dropped when the country code is
        // added, so 069... becomes +35569...
        if (compact.startsWith("0")) {
            return ALBANIA_PREFIX + compact.substring(1);
        }

        // Country code typed without the plus.
        if (compact.startsWith(ALBANIA_COUNTRY_CODE)) {
            return "+" + compact;
        }

        /*
         * A bare subscriber number. Albanian mobiles are nine digits starting
         * 6, so this is safe to attribute to Albania; anything else is left as
         * typed rather than guessed at.
         */
        if (compact.length() == 9 && compact.startsWith("6")) {
            return ALBANIA_PREFIX + compact;
        }

        return compact;
    }
}
