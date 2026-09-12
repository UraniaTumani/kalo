package com.kalo.ride;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.PaymentMethod;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every rule that decides whether a company is offered to a passenger.
 *
 * Each test changes exactly one thing away from a company that would otherwise
 * be returned, so a failure names the rule that broke.
 */
@DisplayName("Taxi search availability rules")
class RideSearchIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "pickupAddress": "Rruga e Kavajes",
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300,
              "destinationAddress": "Sheshi Skenderbej"
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    private void expectOptions(User customer, int count) throws Exception {

        mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(tokenFor(customer)))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxiOptions.length()").value(count));
    }

    @Test
    @DisplayName("an approved company with an online driver is offered")
    void bookableCompanyIsReturned() throws Exception {

        fixtures.bookableCompany();

        mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SEARCHING"))
                .andExpect(jsonPath("$.taxiOptions.length()").value(1))
                .andExpect(jsonPath("$.taxiOptions[0].offerId").exists())
                .andExpect(jsonPath("$.taxiOptions[0].distanceKm").exists())
                .andExpect(jsonPath("$.taxiOptions[0].paymentMethods").isArray())
                .andExpect(jsonPath("$.taxiOptions[0].pricingNote").value("Final price by taximeter"));
    }

    @Test
    @DisplayName("a company awaiting verification is excluded")
    void unapprovedCompanyExcluded() throws Exception {

        bookableCompanyWith(VerificationStatus.PENDING, CompanyStatus.INACTIVE,
                true, Set.of(PaymentMethod.CASH));

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a suspended company is excluded")
    void suspendedCompanyExcluded() throws Exception {

        bookableCompanyWith(VerificationStatus.APPROVED, CompanyStatus.SUSPENDED,
                true, Set.of(PaymentMethod.CASH));

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a company with bookings turned off is excluded")
    void bookingDisabledExcluded() throws Exception {

        bookableCompanyWith(VerificationStatus.APPROVED, CompanyStatus.ACTIVE,
                false, Set.of(PaymentMethod.CASH));

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a company with no payment method is excluded")
    void noPaymentMethodExcluded() throws Exception {

        bookableCompanyWith(VerificationStatus.APPROVED, CompanyStatus.ACTIVE,
                true, Set.of());

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a closed company is excluded")
    void closedCompanyExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        fixtures.closeAllWeek(company);

        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
        fixtures.freshLocation(driver);

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("an offline driver is not offered")
    void offlineDriverExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
        fixtures.freshLocation(driver);

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a driver already on a ride is not offered")
    void busyDriverExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.BUSY);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
        fixtures.freshLocation(driver);

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a deactivated driver is not offered even while marked online")
    void inactiveDriverExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.INACTIVE, DriverAvailabilityStatus.ONLINE);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
        fixtures.freshLocation(driver);

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a driver whose position is stale is not offered")
    void staleLocationExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
        fixtures.staleLocation(driver);

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a driver with no position at all is not offered")
    void missingLocationExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a driver with no active vehicle assignment is not offered")
    void noAssignmentExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        fixtures.freshLocation(driver);

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a driver assigned to a deactivated vehicle is not offered")
    void inactiveVehicleExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.INACTIVE);
        fixtures.assign(driver, vehicle);
        fixtures.freshLocation(driver);

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("a driver far outside the search radius is not offered")
    void driverOutsideRadiusExcluded() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));

        // Durres, roughly 30 km from the Tirana pickup used by these tests.
        fixtures.location(driver, 41.3231, 19.4414, java.time.Instant.now());

        expectOptions(fixtures.customer(), 0);
    }

    @Test
    @DisplayName("each company appears once, represented by its nearest driver")
    void oneOptionPerCompany() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();

        for (int i = 0; i < 3; i++) {
            Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
            fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
            fixtures.freshLocation(driver);
        }

        expectOptions(fixtures.customer(), 1);
    }

    @Test
    @DisplayName("two bookable companies produce two options")
    void twoCompaniesProduceTwoOptions() throws Exception {

        fixtures.bookableCompany();
        fixtures.bookableCompany();

        expectOptions(fixtures.customer(), 2);
    }

    @Test
    @DisplayName("a customer with a ride already in progress cannot search again")
    void cannotSearchWithActiveRide() throws Exception {

        fixtures.bookableCompany();
        User customer = fixtures.customer();
        String token = tokenFor(customer);

        String search = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long requestId = ((Number) com.jayway.jsonpath.JsonPath.read(search, "$.rideRequestId")).longValue();
        long offerId = ((Number) com.jayway.jsonpath.JsonPath.read(search, "$.taxiOptions[0].offerId")).longValue();

        mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"offerId":%d}
                                        """.formatted(offerId))
                )
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isConflict());
    }

    /* ------------------------------------------------------------ helpers */

    private void bookableCompanyWith(
            VerificationStatus verification,
            CompanyStatus status,
            boolean bookingEnabled,
            Set<PaymentMethod> paymentMethods
    ) {

        TaxiCompany company = fixtures.company(verification, status, bookingEnabled, paymentMethods);
        Driver driver = fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        fixtures.assign(driver, fixtures.vehicle(company, VehicleStatus.ACTIVE));
        fixtures.freshLocation(driver);
    }
}
