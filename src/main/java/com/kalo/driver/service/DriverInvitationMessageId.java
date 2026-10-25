package com.kalo.driver.service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
/** Stable, non-secret provider correlation ID, reconstructible without the token. */
public final class DriverInvitationMessageId {
    private DriverInvitationMessageId() {}
    public static String create(String destination, String digest, String requestKey) {
        if (destination == null || !destination.matches("\\+[1-9][0-9]{6,14}")
                || digest == null || !digest.matches("[0-9a-f]{64}")
                || requestKey == null || !requestKey.matches("driver-invitation-[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid invitation message identity");
        }
        return UUID.nameUUIDFromBytes(("kalo-driver-v1|" + requestKey + "|"
                + destination.substring(1) + "|" + digest).getBytes(StandardCharsets.US_ASCII)).toString();
    }
}
