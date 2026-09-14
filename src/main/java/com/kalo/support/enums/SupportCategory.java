package com.kalo.support.enums;

/**
 * What the request is about, chosen by the person raising it.
 *
 * Deliberately short. A long list makes the submitter guess, and every value
 * here is one a KALO operator would actually route differently.
 */
public enum SupportCategory {
    RIDE_ISSUE,
    PAYMENT,
    ACCOUNT,
    DRIVER_OR_VEHICLE,
    TECHNICAL,
    OTHER
}
