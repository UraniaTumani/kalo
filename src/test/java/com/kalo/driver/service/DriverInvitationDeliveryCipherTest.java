package com.kalo.driver.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class DriverInvitationDeliveryCipherTest {
    private final byte[] key = new byte[32];
    private final DriverInvitationDeliveryCipher cipher = new DriverInvitationDeliveryCipher(key);
    private final String token = new DriverInvitationTokenCodec().generate();
    private final String phone = "+355690000001";
    private final Instant expiry = Instant.parse("2026-10-25T17:00:00Z");

    @Test
    void roundTripDoesNotExposeTokenInEnvelope() {
        String envelope = encrypt();
        assertThat(envelope).startsWith("v1.").doesNotContain(token);
        assertThat(cipher.decrypt(envelope, 1, 2, phone, expiry)).isEqualTo(token);
    }

    @Test
    void usesFreshNonceForEveryEncryption() {
        assertThat(encrypt()).isNotEqualTo(encrypt());
    }

    @Test
    void detectsCiphertextTampering() {
        byte[] bytes = Base64.getUrlDecoder().decode(encrypt().substring(3));
        bytes[20] ^= 1;
        rejects("v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Test
    void detectsNonceTampering() {
        byte[] bytes = Base64.getUrlDecoder().decode(encrypt().substring(3));
        bytes[0] ^= 1;
        rejects("v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Test
    void rejectsWrongKey() {
        byte[] other = new byte[32];
        other[0] = 1;
        var wrongCipher = new DriverInvitationDeliveryCipher(other);
        assertThatThrownBy(() -> wrongCipher.decrypt(encrypt(), 1, 2, phone, expiry))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid invitation delivery envelope");
    }

    @Test
    void bindsInvitationDriverDestinationAndExpiry() {
        String envelope = encrypt();
        assertThatThrownBy(() -> cipher.decrypt(envelope, 3, 2, phone, expiry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(envelope, 1, 3, phone, expiry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(envelope, 1, 2, "+355690000002", expiry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(envelope, 1, 2, phone, expiry.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedOrUnsupportedEnvelopesWithoutEchoingInput() {
        for (String invalid : new String[]{null, "", "secret", "v2." + encrypt().substring(3), "v1." + "!".repeat(95)}) {
            rejects(invalid);
        }
    }

    @Test
    void rejectsInvalidTokenAndContext() {
        assertThatThrownBy(() -> cipher.encrypt("secret", 1, 2, phone, expiry))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid invitation token");
        assertThatThrownBy(() -> cipher.encrypt(token, 0, 2, phone, expiry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(token, 1, 2, "bad", expiry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(token, 1, 2, phone, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAes256KeyAndDefensivelyCopiesIt() {
        assertThatThrownBy(() -> new DriverInvitationDeliveryCipher(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DriverInvitationDeliveryCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        String envelope = encrypt();
        key[0] = 1;
        assertThat(cipher.decrypt(envelope, 1, 2, phone, expiry)).isEqualTo(token);
    }

    private String encrypt() {
        return cipher.encrypt(token, 1, 2, phone, expiry);
    }

    private void rejects(String envelope) {
        assertThatThrownBy(() -> cipher.decrypt(envelope, 1, 2, phone, expiry))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid invitation delivery envelope");
    }
}
