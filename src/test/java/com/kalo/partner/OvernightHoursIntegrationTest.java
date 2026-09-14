package com.kalo.partner;

import com.kalo.partner.entity.CompanyOperatingHours;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.CompanyOperatingHoursRepository;
import com.kalo.partner.service.CompanyAvailabilityChecker;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an overnight shift actually means once it is saved.
 *
 * Being able to store 20:00 -> 04:00 is worth nothing on its own; what matters
 * is whether a passenger searching at two in the morning is offered the company.
 * These tests drive {@link CompanyAvailabilityChecker} with a fixed instant
 * rather than the wall clock, so they assert the rule itself instead of
 * whatever time the suite happens to run at.
 */
@DisplayName("Overnight operating hours")
class OvernightHoursIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId TIRANE = ZoneId.of("Europe/Tirane");

    /** Inside the seeded service area, so only the hours decide the answer. */
    private static final double PICKUP_LAT = 41.3275;
    private static final double PICKUP_LNG = 19.8187;

    @Autowired
    CompanyAvailabilityChecker availabilityChecker;

    @Autowired
    CompanyOperatingHoursRepository operatingHoursRepository;

    /** A known Monday, so the day-of-week assertions are not calendar-dependent. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

    private Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), TIRANE).toInstant();
    }

    /** Replaces the whole week with one shift, repeated every day. */
    private TaxiCompany companyWorking(LocalTime open, LocalTime close) {

        TaxiCompany company = fixtures.approvedCompany();

        operatingHoursRepository.deleteAll(
                operatingHoursRepository.findAllByCompanyIdOrderByDayOfWeek(company.getId())
        );

        for (DayOfWeek day : DayOfWeek.values()) {
            CompanyOperatingHours hours = new CompanyOperatingHours();
            hours.setCompany(company);
            hours.setDayOfWeek(day);
            hours.setOpenTime(open);
            hours.setCloseTime(close);
            hours.setClosed(false);
            operatingHoursRepository.save(hours);
        }

        return company;
    }

    private boolean availableAt(TaxiCompany company, Instant when) {
        return availabilityChecker.isAvailable(company, PICKUP_LAT, PICKUP_LNG, when);
    }

    @Test
    @DisplayName("a 20:00 to 04:00 company is open late at night and in the small hours")
    void overnightCompanyIsOpenAcrossMidnight() {

        TaxiCompany company = companyWorking(LocalTime.of(20, 0), LocalTime.of(4, 0));

        assertThat(availableAt(company, at(MONDAY, 22, 0)))
                .as("22:00, well inside the shift")
                .isTrue();

        assertThat(availableAt(company, at(MONDAY, 20, 0)))
                .as("20:00, the moment it opens")
                .isTrue();

        assertThat(availableAt(company, at(MONDAY.plusDays(1), 2, 0)))
                .as("02:00, still the same night's work")
                .isTrue();

        assertThat(availableAt(company, at(MONDAY.plusDays(1), 3, 59)))
                .as("03:59, the last minute before closing")
                .isTrue();
    }

    @Test
    @DisplayName("the same company is shut during the day")
    void overnightCompanyIsClosedInDaylight() {

        TaxiCompany company = companyWorking(LocalTime.of(20, 0), LocalTime.of(4, 0));

        assertThat(availableAt(company, at(MONDAY, 4, 0)))
                .as("04:00, the moment it closes")
                .isFalse();

        assertThat(availableAt(company, at(MONDAY, 12, 0)))
                .as("midday")
                .isFalse();

        assertThat(availableAt(company, at(MONDAY, 19, 59)))
                .as("19:59, a minute before opening")
                .isFalse();
    }

    @Test
    @DisplayName("equal times mean open at every hour of the week")
    void allDayCompanyIsAlwaysOpen() {

        TaxiCompany company = companyWorking(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);

        for (int hour = 0; hour < 24; hour++) {
            assertThat(availableAt(company, at(MONDAY, hour, 30)))
                    .as("hour %d", hour)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an ordinary daytime company is unaffected")
    void normalHoursStillBehave() {

        TaxiCompany company = companyWorking(LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThat(availableAt(company, at(MONDAY, 12, 0))).isTrue();
        assertThat(availableAt(company, at(MONDAY, 7, 59))).isFalse();
        assertThat(availableAt(company, at(MONDAY, 18, 0))).isFalse();
        assertThat(availableAt(company, at(MONDAY, 2, 0))).isFalse();
    }

    /**
     * Pins a semantic that is easy to misread, and that this change does not
     * alter: each day's row is read on its own.
     *
     * "Monday 20:00 -> 04:00" therefore means "late on Monday, and early on
     * Monday" — not "Monday night running into Tuesday morning". A company
     * that works nights all week cannot tell the difference, because every
     * day carries the same shift. It only shows at the edge of a week: with
     * Monday open 20:00 -> 04:00 and Tuesday closed, nobody is offered a ride
     * at 02:00 on Tuesday even though a driver is arguably still out.
     *
     * Left as it is deliberately — changing it would alter who is offered a
     * ride, which is ride logic, not this fix.
     */
    @Test
    @DisplayName("each day is read on its own, so a closed Tuesday shuts the Monday night shift out")
    void overnightDoesNotSpillIntoAClosedNextDay() {

        TaxiCompany company = companyWorking(LocalTime.of(20, 0), LocalTime.of(4, 0));

        CompanyOperatingHours tuesday =
                operatingHoursRepository
                        .findAllByCompanyIdOrderByDayOfWeek(company.getId())
                        .stream()
                        .filter(hours -> hours.getDayOfWeek() == DayOfWeek.TUESDAY)
                        .findFirst()
                        .orElseThrow();

        tuesday.setClosed(true);
        operatingHoursRepository.save(tuesday);

        assertThat(availableAt(company, at(MONDAY, 22, 0)))
                .as("Monday night is unaffected")
                .isTrue();

        assertThat(availableAt(company, at(MONDAY.plusDays(1), 2, 0)))
                .as("Tuesday 02:00 reads Tuesday's row, which is closed")
                .isFalse();
    }
}
