package com.kalo.partner;

import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The partner fleet lists, now that they are paged.
 *
 * Two things are worth proving and neither is the paging itself: that a page
 * parameter is not a way into another company's fleet, and that the filters the
 * assignment and ride pickers depend on actually narrow what comes back. A
 * picker that silently loses a driver behind a page boundary makes them
 * unassignable, which is the failure this whole change exists to avoid.
 */
@DisplayName("Partner fleet lists")
class FleetPaginationIntegrationTest extends AbstractIntegrationTest {

    @Nested
    @DisplayName("Drivers")
    class Drivers {

        @Test
        @DisplayName("comes back as a page, and size is honoured")
        void isPaged() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            for (int i = 0; i < 3; i++) {
                fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
            }

            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .param("size", "2")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }

        @Test
        @DisplayName("a second page holds the rest")
        void secondPage() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            for (int i = 0; i < 3; i++) {
                fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
            }

            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .param("size", "2")
                                    .param("page", "1")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        @Test
        @DisplayName("never reaches another company's drivers")
        void isScopedToTheCompany() throws Exception {

            TaxiCompany mine = fixtures.approvedCompany();
            TaxiCompany theirs = fixtures.approvedCompany();

            fixtures.driver(mine, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
            fixtures.driver(theirs, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
            fixtures.driver(theirs, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);

            // Scoping lives in the query, so a generous page size must not widen it.
            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .param("size", "100")
                                    .header("Authorization", bearer(tokenFor(mine.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("availabilityStatus narrows to what the ride picker can assign")
        void filtersByAvailability() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
            fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);
            fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.BUSY);

            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .param("availabilityStatus", "ONLINE")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].availabilityStatus").value("ONLINE"));
        }

        @Test
        @DisplayName("status and availabilityStatus combine")
        void filtersCombine() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
            fixtures.driver(company, DriverStatus.INACTIVE, DriverAvailabilityStatus.ONLINE);

            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .param("status", "ACTIVE")
                                    .param("availabilityStatus", "ONLINE")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("unassigned excludes drivers already holding a vehicle")
        void filtersUnassigned() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            Driver held = fixtures.driver(
                    company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE
            );
            fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE);

            fixtures.assign(held, fixtures.vehicle(company, VehicleStatus.ACTIVE));

            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .param("unassigned", "true")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("without the filter, an assigned driver is still listed")
        void unassignedIsOptional() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            Driver held = fixtures.driver(
                    company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE
            );
            fixtures.assign(held, fixtures.vehicle(company, VehicleStatus.ACTIVE));

            mockMvc.perform(
                            get("/api/v1/partner/drivers")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    @Nested
    @DisplayName("Vehicles")
    class Vehicles {

        @Test
        @DisplayName("comes back as a page")
        void isPaged() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            fixtures.vehicle(company, VehicleStatus.ACTIVE);
            fixtures.vehicle(company, VehicleStatus.ACTIVE);

            mockMvc.perform(
                            get("/api/v1/partner/vehicles")
                                    .param("size", "1")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("never reaches another company's vehicles")
        void isScopedToTheCompany() throws Exception {

            TaxiCompany mine = fixtures.approvedCompany();
            TaxiCompany theirs = fixtures.approvedCompany();

            fixtures.vehicle(mine, VehicleStatus.ACTIVE);
            fixtures.vehicle(theirs, VehicleStatus.ACTIVE);

            mockMvc.perform(
                            get("/api/v1/partner/vehicles")
                                    .param("size", "100")
                                    .header("Authorization", bearer(tokenFor(mine.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("unassigned excludes vehicles a driver already holds")
        void filtersUnassigned() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            Vehicle held = fixtures.vehicle(company, VehicleStatus.ACTIVE);
            fixtures.vehicle(company, VehicleStatus.ACTIVE);

            fixtures.assign(
                    fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE),
                    held
            );

            mockMvc.perform(
                            get("/api/v1/partner/vehicles")
                                    .param("unassigned", "true")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    @Nested
    @DisplayName("Assignments")
    class Assignments {

        @Test
        @DisplayName("comes back as a page")
        void isPaged() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            for (int i = 0; i < 2; i++) {
                fixtures.assign(
                        fixtures.driver(
                                company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE
                        ),
                        fixtures.vehicle(company, VehicleStatus.ACTIVE)
                );
            }

            mockMvc.perform(
                            get("/api/v1/partner/driver-vehicle-assignments")
                                    .param("size", "1")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("never reaches another company's assignments")
        void isScopedToTheCompany() throws Exception {

            TaxiCompany mine = fixtures.approvedCompany();
            TaxiCompany theirs = fixtures.approvedCompany();

            fixtures.assign(
                    fixtures.driver(mine, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE),
                    fixtures.vehicle(mine, VehicleStatus.ACTIVE)
            );
            fixtures.assign(
                    fixtures.driver(theirs, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE),
                    fixtures.vehicle(theirs, VehicleStatus.ACTIVE)
            );

            mockMvc.perform(
                            get("/api/v1/partner/driver-vehicle-assignments")
                                    .param("size", "100")
                                    .header("Authorization", bearer(tokenFor(mine.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("active=true hides the history this table accumulates")
        void filtersByActive() throws Exception {

            TaxiCompany company = fixtures.approvedCompany();

            fixtures.assign(
                    fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE),
                    fixtures.vehicle(company, VehicleStatus.ACTIVE)
            );

            /*
             * Unassigning only flips the flag, so this row stays forever. That is
             * the reason the endpoint is paged at all.
             */
            var retired = fixtures.assign(
                    fixtures.driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.OFFLINE),
                    fixtures.vehicle(company, VehicleStatus.ACTIVE)
            );
            retired.setActive(false);
            fixtures.saveAssignment(retired);

            mockMvc.perform(
                            get("/api/v1/partner/driver-vehicle-assignments")
                                    .param("active", "true")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));

            mockMvc.perform(
                            get("/api/v1/partner/driver-vehicle-assignments")
                                    .header("Authorization", bearer(tokenFor(company.getOwner())))
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));
        }
    }
}
