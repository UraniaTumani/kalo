package com.kalo.assignment;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pairing a driver with a car, and taking it back.
 *
 * FleetPaginationIntegrationTest covers how assignments are listed; nothing
 * covered making one. The rules matter because an assignment is what makes a
 * driver dispatchable at all — a wrong one puts a passenger in a car that is
 * not insured, or leaves a company unable to move a car between shifts.
 */
@DisplayName("Driver and vehicle assignments")
class AssignmentIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/partner/driver-vehicle-assignments";

    private String body(Driver driver, Vehicle vehicle) {
        return """
                {"driverId":%d,"vehicleId":%d}
                """.formatted(driver.getId(), vehicle.getId());
    }

    private Driver offlineDriver(TaxiCompany company) {
        return fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
    }

    @Test
    @DisplayName("a driver is paired with a car and the pairing is active")
    void assignCreatesAnActivePairing() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = offlineDriver(company);
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);

        mockMvc.perform(
                        post(PATH)
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(body(driver, vehicle))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.driverId").value(driver.getId()))
                .andExpect(jsonPath("$.vehicleId").value(vehicle.getId()))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("a driver cannot hold two cars at once")
    void oneCarPerDriver() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = offlineDriver(company);
        Vehicle first = fixtures.vehicle(company, VehicleStatus.ACTIVE);
        Vehicle second = fixtures.vehicle(company, VehicleStatus.ACTIVE);
        String token = tokenFor(company.getOwner());

        mockMvc.perform(
                        post(PATH).header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON).content(body(driver, first))
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post(PATH).header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON).content(body(driver, second))
                )
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a car cannot be driven by two people at once")
    void oneDriverPerCar() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver first = offlineDriver(company);
        Driver second = offlineDriver(company);
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);
        String token = tokenFor(company.getOwner());

        mockMvc.perform(
                        post(PATH).header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON).content(body(first, vehicle))
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post(PATH).header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON).content(body(second, vehicle))
                )
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a deactivated driver cannot be given a car")
    void inactiveDriverIsNotEligible() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(
                company, DriverStatus.INACTIVE, DriverAvailabilityStatus.OFFLINE
        );
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);

        mockMvc.perform(
                        post(PATH)
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(body(driver, vehicle))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a deactivated car cannot be handed out")
    void inactiveVehicleIsNotEligible() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = offlineDriver(company);
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.INACTIVE);

        mockMvc.perform(
                        post(PATH)
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(body(driver, vehicle))
                )
                .andExpect(status().is4xxClientError());
    }

    /**
     * Swapping a driver's car mid-shift would strand whatever they are doing,
     * so the driver has to be off the road first.
     */
    @Test
    @DisplayName("a driver who is online cannot be reassigned")
    void onlineDriverCannotBeAssigned() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = fixtures.driver(
                company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE
        );
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);

        mockMvc.perform(
                        post(PATH)
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(body(driver, vehicle))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("releasing a car frees both sides for a new pairing")
    void releaseFreesBothSides() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = offlineDriver(company);
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);
        String token = tokenFor(company.getOwner());

        String created = mockMvc.perform(
                        post(PATH).header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON).content(body(driver, vehicle))
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long assignmentId =
                ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.assignmentId")).longValue();

        mockMvc.perform(
                        delete(PATH + "/" + assignmentId)
                                .header("Authorization", bearer(token))
                )
                .andExpect(status().isNoContent());

        /* The same pair can be made again, which is the point of releasing. */
        mockMvc.perform(
                        post(PATH).header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON).content(body(driver, vehicle))
                )
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("releasing the same assignment twice is refused")
    void doubleReleaseIsRefused() throws Exception {

        TaxiCompany company = fixtures.approvedCompany();
        Driver driver = offlineDriver(company);
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);
        String token = tokenFor(company.getOwner());

        String created = mockMvc.perform(
                        post(PATH).header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON).content(body(driver, vehicle))
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long assignmentId =
                ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.assignmentId")).longValue();

        mockMvc.perform(delete(PATH + "/" + assignmentId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(PATH + "/" + assignmentId).header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a company cannot pair another company's driver with its own car")
    void cannotReachAcrossCompanies() throws Exception {

        TaxiCompany mine = fixtures.approvedCompany();
        TaxiCompany theirs = fixtures.approvedCompany();

        Driver theirDriver = offlineDriver(theirs);
        Vehicle myVehicle = fixtures.vehicle(mine, VehicleStatus.ACTIVE);

        mockMvc.perform(
                        post(PATH)
                                .header("Authorization", bearer(tokenFor(mine.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(body(theirDriver, myVehicle))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a company still in onboarding cannot build a fleet roster")
    void requiresAnApprovedCompany() throws Exception {

        TaxiCompany company = fixtures.company(
                com.kalo.partner.enums.VerificationStatus.PENDING,
                com.kalo.partner.enums.CompanyStatus.INACTIVE,
                true,
                java.util.Set.of(com.kalo.partner.enums.PaymentMethod.CASH)
        );

        Driver driver = offlineDriver(company);
        Vehicle vehicle = fixtures.vehicle(company, VehicleStatus.ACTIVE);

        mockMvc.perform(
                        post(PATH)
                                .header("Authorization", bearer(tokenFor(company.getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content(body(driver, vehicle))
                )
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a customer cannot assign anything")
    void customersAreRefused() throws Exception {

        mockMvc.perform(
                        post(PATH)
                                .header("Authorization", bearer(tokenFor(fixtures.customer())))
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"driverId":1,"vehicleId":1}
                                        """)
                )
                .andExpect(status().isForbidden());
    }
}
