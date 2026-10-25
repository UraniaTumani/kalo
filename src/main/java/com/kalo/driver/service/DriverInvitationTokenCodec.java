package com.kalo.driver.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Invitation-token primitives only: no invitation endpoint or delivery path.
 *
 * Raw tokens must be disclosed only through a future verified delivery channel,
 * never persisted, returned from the partner API or logged.
 */
@Component
public class DriverInvitationTokenCodec {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    /** A fresh 256-bit bearer token, URL-safe for a future activation link. */
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hash a generated token before lookup/storage. Reject malformed input
     * before querying the database, and never include it in an exception.
     */
    public String digest(String token) {
        if (token == null || token.length() != 43) {
            throw new IllegalArgumentException("Invalid invitation token");
        }

        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid invitation token");
        }

        if (decoded.length != TOKEN_BYTES
                || !Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(decoded).equals(token)) {
            throw new IllegalArgumentException("Invalid invitation token");
        }

        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.US_ASCII))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
