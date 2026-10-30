package com.kalo.vehicle;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.util.LicenseValidity;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.service.VehicleEligibility;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VehicleEligibilityTest {
    @Test void datesAreValidThroughToday() {
        var today = LicenseValidity.today();
        assertThatCode(() -> check(1L, VehicleStatus.ACTIVE, today, today, today)).doesNotThrowAnyException();
    }
    @Test void eachMissingOrExpiredDateIsRejected() {
        var valid = LicenseValidity.today().plusYears(1);
        for (LocalDate invalid : new LocalDate[]{null, LicenseValidity.today().minusDays(1)}) {
            assertThatThrownBy(() -> check(1L, VehicleStatus.ACTIVE, invalid, valid, valid)).isInstanceOf(InvalidOperationException.class);
            assertThatThrownBy(() -> check(1L, VehicleStatus.ACTIVE, valid, invalid, valid)).isInstanceOf(InvalidOperationException.class);
            assertThatThrownBy(() -> check(1L, VehicleStatus.ACTIVE, valid, valid, invalid)).isInstanceOf(InvalidOperationException.class);
        }
    }
    @Test void foreignCompanyAndInactiveVehiclesAreRejected() {
        var valid = LicenseValidity.today().plusYears(1);
        assertThatThrownBy(() -> check(2L, VehicleStatus.ACTIVE, valid, valid, valid)).isInstanceOf(InvalidOperationException.class);
        for (var status : new VehicleStatus[]{VehicleStatus.INACTIVE, VehicleStatus.SUSPENDED})
            assertThatThrownBy(() -> check(1L, status, valid, valid, valid)).isInstanceOf(InvalidOperationException.class);
    }
    private void check(Long company, VehicleStatus status, LocalDate registration, LocalDate insurance, LocalDate inspection) {
        VehicleEligibility.requireEligible(1L, company, status, registration, insurance, inspection);
    }
}
