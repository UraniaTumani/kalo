package com.kalo.partner;

import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where and when a company is willing to work.
 *
 * These two settings decide whether a company appears in a customer's search at
 * all, so a rule that fails open here makes a company invisible — or worse,
 * bookable at three in the morning when nobody is driving.
 */
@DisplayName("Partner availability settings")
class AvailabilitySettingsIntegrationTest extends AbstractIntegrationTest {

    @Nested
    @DisplayName("Service area")
    class ServiceArea {

        @Test
        @DisplayName("a partner sets where they will pick up, and reads it back")
        void updateAndRead() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/service-area")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "latitude": 41.3275,
                                              "longitude": 19.8187,
                                              "radiusKm": 15.0,
                                              "timezone": "Europe/Tirane"
                                            }
                                            """)
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.radiusKm").value(15.0));

            mockMvc.perform(
                            get("/api/v1/partner/availability-settings/service-area")
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.latitude").value(41.3275))
                    .andExpect(jsonPath("$.timezone").value("Europe/Tirane"));
        }

        @Test
        @DisplayName("a timezone that does not exist is refused")
        void rejectsUnknownTimezone() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            /*
             * Operating hours are interpreted in this zone, so an unreadable one
             * would silently decide the company is closed.
             */
            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/service-area")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "latitude": 41.3275,
                                              "longitude": 19.8187,
                                              "radiusKm": 15.0,
                                              "timezone": "Mars/Olympus_Mons"
                                            }
                                            """)
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a radius beyond the allowed range is refused")
        void rejectsImpossibleRadius() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/service-area")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {
                                              "latitude": 41.3275,
                                              "longitude": 19.8187,
                                              "radiusKm": 500.0,
                                              "timezone": "Europe/Tirane"
                                            }
                                            """)
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a customer cannot reach the partner settings")
        void isPartnerOnly() throws Exception {

            mockMvc.perform(
                            get("/api/v1/partner/availability-settings/service-area")
                                    .header("Authorization", bearer(tokenFor(fixtures.customer())))
                    )
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Operating hours")
    class OperatingHours {

        @Test
        @DisplayName("a partner sets a week, and reads it back")
        void updateAndRead() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content(week())
                    )
                    .andExpect(status().isOk());

            mockMvc.perform(
                            get("/api/v1/partner/availability-settings/operating-hours")
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("a partner can change hours they have already saved")
        void canEditHoursMoreThanOnce() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            /*
             * Regression. Rewriting a week is delete-then-insert, and Hibernate
             * flushes inserts before deletes, so the old Monday row was still
             * there when the new one arrived: every save after the first failed
             * on the unique constraint with an opaque 409. A partner could set
             * their opening hours exactly once and never correct them.
             */
            for (int save = 0; save < 3; save++) {
                mockMvc.perform(
                                put("/api/v1/partner/availability-settings/operating-hours")
                                        .header("Authorization", bearer(token))
                                        .contentType(APPLICATION_JSON)
                                        .content(week())
                        )
                        .andExpect(status().isOk());
            }

            // The rewrite replaces the week rather than accumulating it.
            mockMvc.perform(
                            get("/api/v1/partner/availability-settings/operating-hours")
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("the same day twice is refused")
        void rejectsDuplicateDay() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            // Two rows for Monday leaves no answer to "are they open on Monday".
            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"hours":[
                                              {"dayOfWeek":"MONDAY","closed":false,
                                               "openTime":"08:00:00","closeTime":"18:00:00"},
                                              {"dayOfWeek":"MONDAY","closed":false,
                                               "openTime":"09:00:00","closeTime":"17:00:00"}
                                            ]}
                                            """)
                    )
                    .andExpect(status().isBadRequest());
        }

        /**
         * A night shift is ordinary work for a taxi company, and this used to
         * be refused: the rule wanted close strictly after open, so 20:00 to
         * 04:00 could not be expressed at all.
         */
        @Test
        @DisplayName("a shift that closes the next morning is accepted and read back")
        void acceptsOvernightDay() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"hours":[
                                              {"dayOfWeek":"MONDAY","closed":false,
                                               "openTime":"20:00:00","closeTime":"04:00:00"}
                                            ]}
                                            """)
                    )
                    .andExpect(status().isOk());

            mockMvc.perform(
                            get("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].openTime").value("20:00:00"))
                    .andExpect(jsonPath("$[0].closeTime").value("04:00:00"));
        }

        /**
         * Equal times mean open around the clock. The seeder and the test
         * fixtures have always written rows in this shape, so refusing it made
         * the API disagree with the data the rest of the system runs on.
         */
        @Test
        @DisplayName("equal opening and closing times are accepted as open all day")
        void acceptsAllDay() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"hours":[
                                              {"dayOfWeek":"MONDAY","closed":false,
                                               "openTime":"00:00:00","closeTime":"00:00:00"}
                                            ]}
                                            """)
                    )
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an open day with no times is refused")
        void rejectsOpenDayWithoutTimes() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"hours":[
                                              {"dayOfWeek":"MONDAY","closed":false,
                                               "openTime":null,"closeTime":null}
                                            ]}
                                            """)
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a closed day needs no times")
        void allowsClosedDayWithoutTimes() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"hours":[
                                              {"dayOfWeek":"SUNDAY","closed":true,
                                               "openTime":null,"closeTime":null}
                                            ]}
                                            """)
                    )
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an empty week is refused")
        void rejectsEmptyWeek() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(company.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content("{\"hours\":[]}")
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a company still awaiting approval cannot set hours")
        void requiresAnApprovedCompany() throws Exception {

            TaxiCompany pending = fixtures.company(
                    VerificationStatus.PENDING,
                    CompanyStatus.ACTIVE,
                    true,
                    Set.of()
            );

            /*
             * Availability only means something once a company can actually take
             * rides; letting it be configured earlier invites a company to think
             * it is live when it is not.
             */
            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(pending.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content(week())
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a suspended company cannot set hours")
        void requiresAnActiveCompany() throws Exception {

            TaxiCompany suspended = fixtures.company(
                    VerificationStatus.APPROVED,
                    CompanyStatus.SUSPENDED,
                    true,
                    Set.of()
            );

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header(
                                            "Authorization",
                                            bearer(tokenFor(suspended.getOwner()))
                                    )
                                    .contentType(APPLICATION_JSON)
                                    .content(week())
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("one company's hours never reach another")
        void isScopedToTheCompany() throws Exception {

            TaxiCompany mine = fixtures.approvedCompany();
            TaxiCompany theirs = fixtures.approvedCompany();

            mockMvc.perform(
                            put("/api/v1/partner/availability-settings/operating-hours")
                                    .header("Authorization", bearer(tokenFor(theirs.getOwner())))
                                    .contentType(APPLICATION_JSON)
                                    .content(week())
                    )
                    .andExpect(status().isOk());

            /*
             * The other company rewrote its week to two days. This one still has
             * the seven it started with — the delete is scoped by company id, and
             * a bulk delete that was not would have taken these with it.
             */
            mockMvc.perform(
                            get("/api/v1/partner/availability-settings/operating-hours")
                                    .header("Authorization", bearer(tokenFor(mine.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(7));
        }

        private String week() {
            return """
                    {"hours":[
                      {"dayOfWeek":"MONDAY","closed":false,
                       "openTime":"08:00:00","closeTime":"18:00:00"},
                      {"dayOfWeek":"TUESDAY","closed":false,
                       "openTime":"08:00:00","closeTime":"18:00:00"}
                    ]}
                    """;
        }
    }

    @Nested
    @DisplayName("Access")
    class Access {

        @Test
        @DisplayName("an anonymous caller gets nothing")
        void requiresAuthentication() throws Exception {

            mockMvc.perform(get("/api/v1/partner/availability-settings/operating-hours"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an admin is not a partner")
        void adminIsNotAPartner() throws Exception {

            User admin = fixtures.admin();

            mockMvc.perform(
                            get("/api/v1/partner/availability-settings/operating-hours")
                                    .header("Authorization", bearer(tokenFor(admin)))
                    )
                    .andExpect(status().isForbidden());
        }
    }
}
