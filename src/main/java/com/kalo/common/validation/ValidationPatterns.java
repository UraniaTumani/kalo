package com.kalo.common.validation;

public final class ValidationPatterns {

    /**
     * Six to twenty digits, optionally starting with '+', and tolerant of the
     * spaces, dashes, dots and brackets people actually type.
     *
     * Separators are accepted rather than rejected because
     * {@link com.kalo.common.util.PhoneNumberNormalizer} strips them and
     * converts the number to +355… before it is stored or compared. Rejecting
     * "+355 69 123 4567" would have pushed that formatting problem onto the
     * user for no benefit, since what lands in the database is canonical
     * either way.
     */
    public static final String PHONE =
            "^\\+?[0-9](?:[0-9\\s().-]*[0-9]){5,24}$";

    public static final String PHONE_MESSAGE =
            "Enter a valid phone number, for example +355 69 123 4567";

    private ValidationPatterns() {
    }
}
