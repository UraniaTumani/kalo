package com.kalo.partner;

import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating and changing the fleet itself.
 *
 * The existing suites cover how a fleet is *listed* (FleetPagination) and which
 * drivers a search will offer (RideSearch). What nothing covered was the way a
 * partner puts a driver or a car into the system in the first place, which is
 * the first thing a real company does and the first place a bad rule would be
 * felt.
 */
@DisplayName("Fleet management")
class FleetManagementIntegrationTest extends AbstractIntegrationTest {

    private static String driverBody(String phone) {
        return """
                {"firstName":"Ana","lastName":"Hoxha","phone":"%s",
                 "licenseNumber":"AL-%s","licenseExpiryDate":"%s"}
                """.formatted(phone, phone.substring(phone.length() - 4),
                LocalDate.now().plusYears(2));
    }

    private static String vehicleBody(String plate) {
        return """
                {"plateNumber":"%s","brand":"Skoda","model":"Octavia",
                 "manufactureYear":2020,"seats":4,"vehicleType":"STANDARD",
                 "registrationExpiryDate":"%s","insuranceExpiryDate":"%s",
                 "technicalInspectionExpiryDate":"%s"}
                """.formatted(plate,
                LocalDate.now().plusYears(1),
                LocalDate.now().plusYears(1),
                LocalDate.now().plusYears(1));
    }

    @Nested
    @DisplayName("Drivers")
    class Drivers {

        @Test
        @DisplayName("a partner adds a driver and reads it back")
        void createAndRead() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            String created = mockMvc.perform(
                            post("/api/v1/partner/drivers")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content(driverBody("+355691110001"))
                    )
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.firstName").value("Ana"))
                    /* A new driver is not yet driving anything. */
                    .andExpect(jsonPath("$.availabilityStatus").value("OFFLINE"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            long driverId = readId(created);

            mockMvc.perform(
                            get("/api/v1/partner/drivers/" + driverId)
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phone").value("+355691110001"));
        }

        @Test
        @DisplayName("a licence that has already expired is refused")
        void rejectsExpiredLicence() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            post("/api/v1/partner/drivers")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"firstName":"Ana","lastName":"Hoxha",
                                             "phone":"+355691110002","licenseNumber":"AL-2",
                                             "licenseExpiryDate":"%s"}
                                            """.formatted(LocalDate.now().minusDays(1)))
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a malformed phone never reaches the service")
        void rejectsMalformedPhone() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            post("/api/v1/partner/drivers")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                                    .contentType(APPLICATION_JSON)
                                    .content(driverBody("not-a-phone"))
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a driver can be deactivated and brought back")
        void deactivateAndReactivate() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            long driverId = readId(
                    mockMvc.perform(
                                    post("/api/v1/partner/drivers")
                                            .header("Authorization", bearer(token))
                                            .contentType(APPLICATION_JSON)
                                            .content(driverBody("+355691110003"))
                            )
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString()
            );

            mockMvc.perform(
                            put("/api/v1/partner/drivers/" + driverId)
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"firstName":"Ana","lastName":"Hoxha",
                                             "phone":"+355691110003","licenseNumber":"AL-0003",
                                             "licenseExpiryDate":"%s","status":"INACTIVE"}
                                            """.formatted(LocalDate.now().plusYears(2)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("INACTIVE"));

            mockMvc.perform(
                            put("/api/v1/partner/drivers/" + driverId)
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"firstName":"Ana","lastName":"Hoxha",
                                             "phone":"+355691110003","licenseNumber":"AL-0003",
                                             "licenseExpiryDate":"%s","status":"ACTIVE"}
                                            """.formatted(LocalDate.now().plusYears(2)))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        /**
         * Going ONLINE is a claim that this driver can be sent to a passenger,
         * and a driver with no car cannot be sent anywhere.
         */
        @Test
        @DisplayName("a driver with no vehicle cannot be put online")
        void onlineNeedsAVehicle() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            long driverId = readId(
                    mockMvc.perform(
                                    post("/api/v1/partner/drivers")
                                            .header("Authorization", bearer(token))
                                            .contentType(APPLICATION_JSON)
                                            .content(driverBody("+355691110004"))
                            )
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString()
            );

            mockMvc.perform(
                            patch("/api/v1/partner/drivers/" + driverId + "/availability")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"availabilityStatus":"ONLINE"}
                                            """)
                    )
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("one company cannot read another company's driver")
        void driversAreScopedToTheCompany() throws Exception {

            TaxiCompany mine = fixtures.approvedCompany();
            TaxiCompany theirs = fixtures.approvedCompany();

            long driverId = readId(
                    mockMvc.perform(
                                    post("/api/v1/partner/drivers")
                                            .header("Authorization", bearer(tokenFor(mine.getOwner())))
                                            .contentType(APPLICATION_JSON)
                                            .content(driverBody("+355691110005"))
                            )
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString()
            );

            mockMvc.perform(
                            get("/api/v1/partner/drivers/" + driverId)
                                    .header("Authorization", bearer(tokenFor(theirs.getOwner())))
                    )
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("Vehicles")
    class Vehicles {

        @Test
        @DisplayName("a partner adds a vehicle with its paperwork and reads it back")
        void createAndRead() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            long vehicleId = readId(
                    mockMvc.perform(
                                    post("/api/v1/partner/vehicles")
                                            .header("Authorization", bearer(token))
                                            .contentType(APPLICATION_JSON)
                                            .content(vehicleBody("AA111BB"))
                            )
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.plateNumber").value("AA111BB"))
                            .andExpect(jsonPath("$.status").value("ACTIVE"))
                            .andReturn().getResponse().getContentAsString()
            );

            /*
             * The expiry dates were on the record but never returned to the
             * screen for a while; asserting them here keeps that honest.
             */
            mockMvc.perform(
                            get("/api/v1/partner/vehicles/" + vehicleId)
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.registrationExpiryDate").isNotEmpty())
                    .andExpect(jsonPath("$.insuranceExpiryDate").isNotEmpty())
                    .andExpect(jsonPath("$.technicalInspectionExpiryDate").isNotEmpty());
        }

        @Test
        @DisplayName("the same plate cannot be registered twice")
        void rejectsDuplicatePlate() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();
            String token = tokenFor(company.getOwner());

            mockMvc.perform(
                            post("/api/v1/partner/vehicles")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content(vehicleBody("BB222CC"))
                    )
                    .andExpect(status().isCreated());

            mockMvc.perform(
                            post("/api/v1/partner/vehicles")
                                    .header("Authorization", bearer(token))
                                    .contentType(APPLICATION_JSON)
                                    .content(vehicleBody("BB222CC"))
                    )
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("a car too old to be plausible is refused")
        void rejectsImpossibleYear() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            post("/api/v1/partner/vehicles")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"plateNumber":"CC333DD","brand":"Ford","model":"T",
                                             "manufactureYear":1850,"seats":4,"vehicleType":"STANDARD"}
                                            """)
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a seat count nobody could carry is refused")
        void rejectsImpossibleSeats() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            mockMvc.perform(
                            post("/api/v1/partner/vehicles")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                                    .contentType(APPLICATION_JSON)
                                    .content("""
                                            {"plateNumber":"DD444EE","brand":"Bus","model":"Big",
                                             "manufactureYear":2020,"seats":99,"vehicleType":"VAN"}
                                            """)
                    )
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("one company cannot read another company's vehicle")
        void vehiclesAreScopedToTheCompany() throws Exception {

            TaxiCompany mine = fixtures.approvedCompany();
            TaxiCompany theirs = fixtures.approvedCompany();

            long vehicleId = readId(
                    mockMvc.perform(
                                    post("/api/v1/partner/vehicles")
                                            .header("Authorization", bearer(tokenFor(mine.getOwner())))
                                            .contentType(APPLICATION_JSON)
                                            .content(vehicleBody("EE555FF"))
                            )
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString()
            );

            mockMvc.perform(
                            get("/api/v1/partner/vehicles/" + vehicleId)
                                    .header("Authorization", bearer(tokenFor(theirs.getOwner())))
                    )
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("Access")
    class Access {

        @Test
        @DisplayName("a customer cannot manage a fleet")
        void customersAreRefused() throws Exception {

            String token = tokenFor(fixtures.customer());

            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isForbidden());

            mockMvc.perform(
                            get("/api/v1/partner/vehicles")
                                    .header("Authorization", bearer(token))
                    )
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot manage a fleet")
        void anonymousIsRefused() throws Exception {

            mockMvc.perform(get("/api/v1/partner/drivers"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(get("/api/v1/partner/vehicles"))
                    .andExpect(status().isUnauthorized());
        }
    }

    private long readId(String json) {
        return ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.id")).longValue();
    }
}
