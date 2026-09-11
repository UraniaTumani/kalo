package com.kalo.common.validation;

public final class ValidationPatterns {

    /**
     * Digits with an optional leading '+'. Phone numbers are also the login
     * identifier, so the same shape has to hold everywhere one is accepted.
     */
    public static final String PHONE = "^\\+?[0-9]{6,19}$";

    public static final String PHONE_MESSAGE =
            "Phone must contain 6 to 19 digits, optionally starting with '+'";

    private ValidationPatterns() {
    }
}
