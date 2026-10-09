package com.kalo.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The licence rule itself, with no database and no Spring.
 *
 * Three business decisions are encoded in LicenseValidity and each is the kind
 * of thing that gets quietly reversed by a later edit: the zone it judges dates
 * in, whether the expiry date itself still counts, and what a missing date
 * means. Those are cheap to pin here and expensive to discover from a failing
 * integration test.
 */
@DisplayName("Licence validity")
class LicenseValidityTest {

    @Test
    @DisplayName("a licence is valid through its expiry date")
    void validOnTheExpiryDateItself() {

        LocalDate day = LocalDate.of(2026, 10, 9);

        /*
         * The decision that matters most to a driver holding the document: a
         * licence reading 9 October is good for the whole of 9 October, not
         * invalid from the morning of it.
         */
        assertThat(LicenseValidity.isValidOn(day, day))
                .as("a licence expiring today is still valid today")
                .isTrue();
    }

    @Test
    @DisplayName("a licence is invalid from the day after its expiry date")
    void invalidTheDayAfter() {

        LocalDate expiry = LocalDate.of(2026, 10, 9);

        assertThat(LicenseValidity.isValidOn(expiry, expiry.plusDays(1)))
                .as("the day after expiry is the first day it does not count")
                .isFalse();
    }

    @Test
    @DisplayName("a licence expiring in the future is valid")
    void futureExpiryIsValid() {

        LocalDate day = LocalDate.of(2026, 10, 9);

        assertThat(LicenseValidity.isValidOn(day.plusYears(1), day)).isTrue();
    }

    @Test
    @DisplayName("a missing expiry date is not valid")
    void missingExpiryIsNotValid() {

        /*
         * Not a neutral default. taxi_companies.license_expiry_date is nullable
         * and the partner profile update does not require it, so treating null
         * as acceptable would let a company with a lapsed licence clear the
         * field and carry on trading.
         */
        assertThat(LicenseValidity.isValidOn(null, LocalDate.of(2026, 10, 9)))
                .as("a company with no recorded expiry date must not be treated as licensed")
                .isFalse();

        assertThat(LicenseValidity.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("today is read in Europe/Tirane, not the server's zone")
    void todayIsAlbanian() {

        assertThat(LicenseValidity.ZONE.getId()).isEqualTo("Europe/Tirane");

        /*
         * Asserted against the zone rather than against the server default,
         * because on a machine already set to Tirane the two agree and the test
         * would prove nothing. This compares the value the code produces with
         * the value that zone implies.
         */
        assertThat(LicenseValidity.today())
                .isEqualTo(LocalDate.now(LicenseValidity.ZONE));
    }

    @Test
    @DisplayName("the convenience overload agrees with the explicit one")
    void isValidMatchesIsValidOnToday() {

        LocalDate today = LicenseValidity.today();

        assertThat(LicenseValidity.isValid(today))
                .isEqualTo(LicenseValidity.isValidOn(today, today));

        assertThat(LicenseValidity.isValid(today.minusDays(1)))
                .isEqualTo(LicenseValidity.isValidOn(today.minusDays(1), today));
    }
}
