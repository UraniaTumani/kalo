package com.kalo.driver.service;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverInvitationTokenCodecTest {

    private final DriverInvitationTokenCodec codec = new DriverInvitationTokenCodec();

    @Test
    void generatesIndependentHighEntropyTokens() {
        String first = codec.generate();
        String second = codec.generate();

        assertThat(first).hasSize(43).isNotEqualTo(second);
        assertThat(codec.digest(first)).hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(codec.digest(second));
        assertThat(codec.digest(first)).isEqualTo(codec.digest(first));
        assertThat(codec.digest(first)).isNotEqualTo(first);
    }

    @Test
    void rejectsMissingAndMalformedTokensWithoutIncludingCredentials() {
        for (String token : new String[]{ "", "test", "A".repeat(42) + "?",
                "?".repeat(43), " ".repeat(43) }) {
            assertThatThrownBy(() -> codec.digest(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid invitation token");
        }

        assertThatThrownBy(() -> codec.digest(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid invitation token");
    }

    @Test
    void generatedDigestIsHexEncodedAndNotRawToken() {
        String token = codec.generate();
        assertThat(HexFormat.of().parseHex(codec.digest(token))).hasSize(32);
    }
}
