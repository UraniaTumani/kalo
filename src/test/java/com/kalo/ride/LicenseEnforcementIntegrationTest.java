package com.kalo.ride;

import com.jayway.jsonpath.JsonPath;
import com.kalo.common.util.LicenseValidity;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import com.kalo.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Licence validity where it decides something (F36).
 *
 * The finding was that expiry was checked when a driver was written and never
 * again, so a licence could lapse and the driver stayed searchable and
 * assignable indefinitely. Two of the five seeded drivers on the development
 * database were in exactly that state when this was raised.
 *
 * Three gates are covered here because an expired licence has to fail at all
 * three to mean anything: it must not be offered by search, it must not be
 * brought back online, and it must not be accepted onto a ride. The third is
 * the one that cannot be skipped even given the first, because a RideRequest
 * lives for five minutes and a licence expires at midnight.
 *
 * Dates are expressed relative to {@link LicenseValidity#today()} rather than
 * to the server's today, since that is the zone the rule is written in.
 */
@DisplayName("Licence enforcement")
class LicenseEnforcementIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "pickupAddress": "Rruga e Kavajes",
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300,
              "destinationAddress": "Sheshi Skenderbej"
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    TaxiCompanyRepository taxiCompanyRepository;

    @Autowired
    RideRepository rideRepository;

    /* ------------------------------------------------------------- search */

    @Test
    @DisplayName("a company with a valid licence and a valid driver is offered")
    void validLicencesAreOffered() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        assertThat(companiesOfferedToACustomer())
                .as("the control: nothing about this company is wrong")
                .contains(taxi.company().getId());
    }

    @Test
    @DisplayName("a driver whose licence expired yesterday is not offered")
    void expiredDriverLicenceIsNotOffered() throws Exception {

        TestDataFactory.BookableCompany control = fixtures.bookableCompany();
        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        setDriverExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        List<Long> offered = companiesOfferedToACustomer();

        /*
         * The control is what stops this test passing for the wrong reason. A
         * doesNotContain assertion is satisfied by an empty list, so without a
         * company that MUST be present, a search broken in any way at all would
         * look like correct exclusion.
         */
        assertThat(offered)
                .as("a company with valid licences must still be offered")
                .contains(control.company().getId());

        assertThat(offered)
                .as("the company's only driver cannot legally drive")
                .doesNotContain(taxi.company().getId());
    }

    @Test
    @DisplayName("a driver whose licence expires today is still offered")
    void sameDayDriverLicenceIsStillOffered() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        setDriverExpiry(taxi.driver(), LicenseValidity.today());

        /*
         * The boundary, and the reason it is tested at this level rather than
         * only in the unit test: an off-by-one here is a driver losing a day's
         * work, and the comparison happens in SQL rather than in Java.
         */
        assertThat(companiesOfferedToACustomer())
                .as("a licence is valid through its expiry date")
                .contains(taxi.company().getId());
    }

    @Test
    @DisplayName("a company whose own licence expired is not offered")
    void expiredCompanyLicenceIsNotOffered() throws Exception {

        TestDataFactory.BookableCompany control = fixtures.bookableCompany();
        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        setCompanyExpiry(taxi.company(), LicenseValidity.today().minusDays(1));

        List<Long> offered = companiesOfferedToACustomer();

        assertThat(offered).contains(control.company().getId());
        assertThat(offered).doesNotContain(taxi.company().getId());
    }

    @Test
    @DisplayName("a company with no licence expiry date on record is not offered")
    void missingCompanyLicenceIsNotOffered() throws Exception {

        TestDataFactory.BookableCompany control = fixtures.bookableCompany();
        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        setCompanyExpiry(taxi.company(), null);

        List<Long> offered = companiesOfferedToACustomer();

        assertThat(offered).contains(control.company().getId());

        /*
         * Reachable through an ordinary profile edit, because the column is
         * nullable and UpdatePartnerProfileRequest does not require the field.
         * If null were treated as licensed, a company whose licence had lapsed
         * could clear the date and keep trading — so this is the case that
         * decides whether the whole check can be bypassed.
         */
        assertThat(offered)
                .as("no recorded expiry date is not the same as a valid licence")
                .doesNotContain(taxi.company().getId());
    }

    /* ------------------------------------------------------- going online */

    @Test
    @DisplayName("a driver with an expired licence cannot be brought back online")
    void expiredDriverCannotGoOnline() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        setAvailability(taxi.driver(), DriverAvailabilityStatus.OFFLINE);
        setDriverExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        String body = mockMvc.perform(
                        patch("/api/v1/partner/drivers/" + taxi.driver().getId() + "/availability")
                                .header("Authorization", bearer(tokenFor(owner(taxi))))
                                .contentType(APPLICATION_JSON)
                                .content("{\"availabilityStatus\":\"ONLINE\"}")
                )
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        /*
         * The message is checked, not just the status. A partner seeing their
         * driver refused needs to know which field is wrong and what to do, and
         * a bare 400 would send them to support instead of to Settings.
         */
        assertThat(body)
                .as("the refusal must name the expiry date and say what to do")
                .contains("license expired on")
                .contains("Update the license expiry date");

        assertThat(availabilityOf(taxi.driver()))
                .isEqualTo(DriverAvailabilityStatus.OFFLINE);
    }

    @Test
    @DisplayName("a driver whose licence expires today can still be brought online")
    void sameDayDriverCanStillGoOnline() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        setAvailability(taxi.driver(), DriverAvailabilityStatus.OFFLINE);
        setDriverExpiry(taxi.driver(), LicenseValidity.today());

        mockMvc.perform(
                        patch("/api/v1/partner/drivers/" + taxi.driver().getId() + "/availability")
                                .header("Authorization", bearer(tokenFor(owner(taxi))))
                                .contentType(APPLICATION_JSON)
                                .content("{\"availabilityStatus\":\"ONLINE\"}")
                )
                .andExpect(status().isOk());

        assertThat(availabilityOf(taxi.driver()))
                .isEqualTo(DriverAvailabilityStatus.ONLINE);
    }

    /* ------------------------------------------------------- acceptance */

    @Test
    @DisplayName("a ride cannot be accepted with a driver whose licence has expired")
    void cannotAcceptWithExpiredDriverLicence() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        long rideId = requestRideFor(taxi);

        /* Valid when the offer was built, lapsed before it was accepted. */
        setDriverExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        String body = acceptExpectingRefusal(taxi, rideId);

        assertThat(body).contains("license expired on");

        assertThat(rideStatus(rideId))
                .as("a refused acceptance must leave the ride where it was")
                .isEqualTo(RideStatus.REQUESTED);

        assertThat(availabilityOf(taxi.driver()))
                .as("and must not stand the driver down as BUSY")
                .isEqualTo(DriverAvailabilityStatus.ONLINE);
    }

    @Test
    @DisplayName("a ride cannot be accepted while the company's own licence has expired")
    void cannotAcceptWithExpiredCompanyLicence() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        long rideId = requestRideFor(taxi);

        setCompanyExpiry(taxi.company(), LicenseValidity.today().minusDays(1));

        assertThat(acceptExpectingRefusal(taxi, rideId))
                .contains("taxi license expired on");

        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.REQUESTED);
    }

    @Test
    @DisplayName("a ride cannot be accepted when the company has no licence expiry date")
    void cannotAcceptWithMissingCompanyLicence() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        long rideId = requestRideFor(taxi);

        setCompanyExpiry(taxi.company(), null);

        assertThat(acceptExpectingRefusal(taxi, rideId))
                .as("the refusal must tell the partner the date is missing, not merely invalid")
                .contains("no taxi license expiry date on record");

        assertThat(rideStatus(rideId)).isEqualTo(RideStatus.REQUESTED);
    }

    /* --------------------------------------------------- rides in flight */

    @Test
    @DisplayName("a licence expiring mid-ride does not disturb the ride")
    void aRideInFlightIsLeftAlone() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        long rideId = requestRideFor(taxi);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(tokenFor(owner(taxi))))
                                .contentType(APPLICATION_JSON)
                                .content("{\"driverId\":%d}".formatted(taxi.driver().getId()))
                )
                .andExpect(status().isOk());

        /*
         * The licence lapses with a passenger already in the car. The business
         * decision is that the ride finishes: there is no safe way to end a
         * journey in progress from a dispatcher's screen, and stranding somebody
         * mid-route is worse than completing a ride on a document that expired
         * at midnight.
         */
        setDriverExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));
        setCompanyExpiry(taxi.company(), LicenseValidity.today().minusDays(1));

        for (String step : List.of("driver-arriving", "driver-arrived", "start")) {

            mockMvc.perform(
                            post("/api/v1/partner/rides/" + rideId + "/" + step)
                                    .header("Authorization", bearer(tokenFor(owner(taxi))))
                    )
                    .andExpect(status().isOk());
        }

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/complete")
                                .header("Authorization", bearer(tokenFor(owner(taxi))))
                                .contentType(APPLICATION_JSON)
                                .content("{\"finalAmount\":850.0}")
                )
                .andExpect(status().isOk());

        assertThat(rideStatus(rideId))
                .as("every remaining transition must still work")
                .isEqualTo(RideStatus.COMPLETED);

        assertThat(availabilityOf(taxi.driver()))
                .as("completing the ride releases the driver as it always did")
                .isEqualTo(DriverAvailabilityStatus.ONLINE);
    }

    /* ----------------------------------------------------------- helpers */

    private User owner(TestDataFactory.BookableCompany taxi) {
        return taxi.company().getOwner();
    }

    /**
     * Company ids a real customer search would offer right now.
     *
     * The longValue() mapping is not decoration. JsonPath deserialises these
     * ids as Integer, while an entity id is a Long, and Integer.equals(Long) is
     * false however equal the numbers look — so a raw comparison fails for a
     * company that IS offered, and, far worse, a doesNotContain assertion
     * passes for one that is. An earlier version of this file compared the two
     * types directly and its exclusion tests passed while proving nothing.
     */
    private List<Long> companiesOfferedToACustomer() throws Exception {

        String token = tokenFor(fixtures.customer());

        String response = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return companyIds(response);
    }

    private static List<Long> companyIds(String searchResponse) {

        List<Number> raw = JsonPath.read(searchResponse, "$.taxiOptions[*].companyId");

        return raw.stream().map(Number::longValue).toList();
    }

    private long requestRideFor(TestDataFactory.BookableCompany taxi) throws Exception {

        String token = tokenFor(fixtures.customer());

        String search = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long requestId = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();

        /* The offer belonging to this company, not merely the first one. */
        int index = companyIds(search).indexOf(taxi.company().getId());

        assertThat(index)
                .as("the company under test should be bookable before anything is broken")
                .isGreaterThanOrEqualTo(0);

        long offerId = ((Number) JsonPath.read(
                search,
                "$.taxiOptions[" + index + "].offerId"
        )).longValue();

        String selected = mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content("{\"offerId\":%d}".formatted(offerId))
                )
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(selected, "$.rideId")).longValue();
    }

    private String acceptExpectingRefusal(
            TestDataFactory.BookableCompany taxi,
            long rideId
    ) throws Exception {

        return mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(tokenFor(owner(taxi))))
                                .contentType(APPLICATION_JSON)
                                .content("{\"driverId\":%d}".formatted(taxi.driver().getId()))
                )
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
    }

    private void setDriverExpiry(Driver driver, LocalDate expiry) {

        Driver stored = driverRepository.findById(driver.getId()).orElseThrow();
        stored.setLicenseExpiryDate(expiry);
        driverRepository.save(stored);
    }

    private void setCompanyExpiry(TaxiCompany company, LocalDate expiry) {

        TaxiCompany stored = taxiCompanyRepository.findById(company.getId()).orElseThrow();
        stored.setLicenseExpiryDate(expiry);
        taxiCompanyRepository.save(stored);
    }

    private void setAvailability(Driver driver, DriverAvailabilityStatus status) {

        Driver stored = driverRepository.findById(driver.getId()).orElseThrow();
        stored.setAvailabilityStatus(status);
        driverRepository.save(stored);
    }

    private DriverAvailabilityStatus availabilityOf(Driver driver) {
        return driverRepository.findById(driver.getId()).orElseThrow().getAvailabilityStatus();
    }

    private RideStatus rideStatus(long rideId) {
        return rideRepository.findById(rideId).orElseThrow().getStatus();
    }
}
