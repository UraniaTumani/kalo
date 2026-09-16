package com.kalo.location;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reporting where a driver is.
 *
 * Nothing covered this endpoint before, and that is exactly how a blocker
 * reached a production rehearsal: every fixture and the dev seeder create
 * drivers already ONLINE with a position written straight through the
 * repository, so the one sequence a partner actually performs — get a fix,
 * send it, then go online — was the one sequence never exercised.
 */
@DisplayName("Driver location reporting")
class DriverLocationIntegrationTest extends AbstractIntegrationTest {

    private static final String BODY = """
            {"latitude":41.3275,"longitude":19.8187}
            """;

    private String path(Driver driver) {
        return "/api/v1/partner/drivers/" + driver.getId() + "/location";
    }

    /** A driver who can legitimately be put on the road: active, with a car. */
    private Driver readyDriver(TaxiCompany company) {
        Driver driver = fixtures.driver(
                company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE
        );
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);
        fixtures.assign(driver, vehicle);
        return driver;
    }

    /**
     * The bug this file exists for.
     *
     * The partner screen asks the device for a position, sends it, and only
     * then flips availability — because a driver whose fix is stale is not
     * offered to passengers. While an offline driver was refused, that first
     * call failed and the driver never went online at all.
     */
    @Test
    @DisplayName("an offline driver can report a position, then go online")
    void offlineDriverCanReportThenGoOnline() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = readyDriver(company);
        String token = tokenFor(company.getOwner());

        mockMvc.perform(
                        put(path(driver))
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(driver.getId()))
                .andExpect(jsonPath("$.latitude").value(41.3275));

        mockMvc.perform(
                        patch("/api/v1/partner/drivers/" + driver.getId() + "/availability")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"availabilityStatus":"ONLINE"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availabilityStatus").value("ONLINE"));
    }

    @Test
    @DisplayName("the position is persisted and readable")
    void positionIsPersisted() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = readyDriver(company);
        String token = tokenFor(company.getOwner());

        mockMvc.perform(
                        put(path(driver))
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isOk());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM driver_locations WHERE driver_id = ?",
                Integer.class,
                driver.getId()
        );

        assertThat(rows).isEqualTo(1);

        mockMvc.perform(
                        get(path(driver)).header("Authorization", bearer(token))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longitude").value(19.8187));
    }

    /**
     * Relaxing the rule must not have made an offline driver dispatchable.
     * Search filters on availability, not on whether a position exists.
     */
    @Test
    @DisplayName("a position does not make an offline driver bookable")
    void positionDoesNotImplyAvailability() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = readyDriver(company);

        mockMvc.perform(
                        put(path(driver))
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isOk());

        String search = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/rides/search")
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"pickupLatitude":41.3275,"pickupLongitude":19.8187,
                                         "destinationLatitude":41.3200,"destinationLongitude":19.8300}
                                        """)
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(search)
                .as("an offline driver with a fresh position must not be offered")
                .contains("\"taxiOptions\":[]");
    }

    /** Still refused: a deactivated driver has no business reporting anything. */
    @Test
    @DisplayName("a deactivated driver cannot report a position")
    void deactivatedDriverIsRefused() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(
                company, DriverStatus.INACTIVE, DriverAvailabilityStatus.OFFLINE
        );

        mockMvc.perform(
                        put(path(driver))
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("one company cannot move another company's driver")
    void locationIsScopedToTheCompany() throws Exception {

        TaxiCompany mine = fixtures.approvedCompany();
        TaxiCompany theirs = fixtures.approvedCompany();
        Driver theirDriver = readyDriver(theirs);

        mockMvc.perform(
                        put(path(theirDriver))
                                .header("Authorization", bearer(tokenFor(mine.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a customer cannot report a driver position")
    void customersAreRefused() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = readyDriver(company);

        mockMvc.perform(
                        put(path(driver))
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                                .contentType(APPLICATION_JSON)
                                .content(BODY)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("coordinates off the planet are refused")
    void impossibleCoordinatesAreRefused() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = readyDriver(company);

        mockMvc.perform(
                        put(path(driver))
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"latitude":999.0,"longitude":-999.0}
                                        """)
                )
                .andExpect(status().isBadRequest());
    }
}
