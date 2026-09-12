package com.kalo.common;

import com.kalo.common.util.PhoneNumberNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A unit test rather than an integration one: this is pure string handling and
 * gains nothing from a database.
 */
@DisplayName("Phone number normalisation")
class PhoneNumberNormalizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // Already canonical.
            "'+355691234567', '+355691234567'",

            // Separators people actually type.
            "'+355 69 123 4567', '+355691234567'",
            "'+355-69-123-4567', '+355691234567'",
            "'+355 (69) 123.4567', '+355691234567'",

            // National form: only the trunk zero is dropped, so the leading 6
            // of the subscriber number survives.
            "'0691234567', '+355691234567'",
            "'069 123 4567', '+355691234567'",

            // Country code without the plus.
            "'355691234567', '+355691234567'",

            // International prefix.
            "'00355691234567', '+355691234567'",

            // Bare Albanian mobile.
            "'691234567', '+355691234567'",
    })
    @DisplayName("every spelling of one number reaches the same form")
    void normalisesToCanonicalForm(String input, String expected) {
        assertThat(PhoneNumberNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the spellings that matter collapse onto each other")
    void equivalentSpellingsMatch() {

        String canonical = PhoneNumberNormalizer.normalize("+355691234567");

        assertThat(PhoneNumberNormalizer.normalize("355691234567")).isEqualTo(canonical);
        assertThat(PhoneNumberNormalizer.normalize("00355691234567")).isEqualTo(canonical);
        assertThat(PhoneNumberNormalizer.normalize("691234567")).isEqualTo(canonical);
        assertThat(PhoneNumberNormalizer.normalize("+355 69 123 4567")).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+39 06 1234567", "+44 20 7946 0958"})
    @DisplayName("a foreign number keeps its own country code")
    void leavesForeignNumbersAlone(String foreign) {

        String normalized = PhoneNumberNormalizer.normalize(foreign);

        assertThat(normalized).doesNotContain(" ");
        assertThat(normalized).startsWith("+");
        assertThat(normalized).doesNotStartWith("+355");
    }

    @Test
    @DisplayName("null and blank pass through for the validator to reject")
    void passesThroughEmptyInput() {
        assertThat(PhoneNumberNormalizer.normalize(null)).isNull();
        assertThat(PhoneNumberNormalizer.normalize("")).isEmpty();
        assertThat(PhoneNumberNormalizer.normalize("   ")).isEmpty();
    }
}
