package com.kalo.driver.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encryption primitive for future durable invitation delivery, not an enabled queue.
 * The AES-256 key must come from external secrets, separate from the database.
 * Future storage keeps only the token digest in driver_invitations and an encrypted
 * delivery envelope in the queue. Never log plaintext, ciphertext, key or destination.
 * Queue integration must enforce expiry/revocation and purge encrypted payloads.
 * No Spring bean, sender or endpoint is enabled by this class.
 */
public final class DriverInvitationDeliveryCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final DriverInvitationTokenCodec codec = new DriverInvitationTokenCodec();

    public DriverInvitationDeliveryCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("Invitation delivery requires a 256-bit encryption key");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    public String encrypt(String token, long invitationId, long driverId, String phone, Instant expiry) {
        byte[] context = context(invitationId, driverId, phone, expiry);
        codec.digest(token); // Canonical generated token only; exception never includes input.
        byte[] plaintext = token.getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] envelope = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Invitation delivery encryption failed");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public String decrypt(String envelope, long invitationId, long driverId, String phone, Instant expiry) {
        byte[] context = context(invitationId, driverId, phone, expiry);
        // 12-byte nonce + 43-byte token + 16-byte authentication tag = 71 bytes.
        if (envelope == null || envelope.length() != 98 || !envelope.startsWith("v1.")) {
            throw invalidEnvelope();
        }
        byte[] plaintext = null;
        try {
            String encoded = envelope.substring(3);
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length != 71 || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes).equals(encoded)) {
                throw invalidEnvelope();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            cipher.updateAAD(context);
            plaintext = cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES);
            String token = new String(plaintext, StandardCharsets.US_ASCII);
            codec.digest(token);
            return token;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            // Do not attach provider exceptions or their payloads to logs.
            throw invalidEnvelope();
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static byte[] context(long invitationId, long driverId, String phone, Instant expiry) {
        if (invitationId <= 0 || driverId <= 0 || phone == null
                || !phone.matches("\\+[1-9][0-9]{6,14}") || expiry == null) {
            throw new IllegalArgumentException("Invalid invitation delivery context");
        }
        return ("kalo-driver-invitation-v1|" + invitationId + "|" + driverId + "|"
                + phone + "|" + expiry).getBytes(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalidEnvelope() {
        return new IllegalArgumentException("Invalid invitation delivery envelope");
    }
}
