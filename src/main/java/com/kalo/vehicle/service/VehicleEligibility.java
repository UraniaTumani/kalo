package com.kalo.vehicle.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.util.LicenseValidity;
import com.kalo.vehicle.enums.VehicleStatus;
import java.time.LocalDate;

/** Operational dates only; this does not certify uploaded documents. */
public final class VehicleEligibility {
    private VehicleEligibility() {}

    public static void requireEligible(Long expectedCompany, Long actualCompany, VehicleStatus status,
                                       LocalDate registration, LocalDate insurance, LocalDate inspection) {
        if (expectedCompany == null || !expectedCompany.equals(actualCompany))
            throw new InvalidOperationException("Assigned vehicle does not belong to this taxi company");
        if (status != VehicleStatus.ACTIVE)
            throw new InvalidOperationException("Assigned vehicle is not active");
        LocalDate today = LicenseValidity.today();
        requireDate(registration, today, "registration");
        requireDate(insurance, today, "insurance");
        requireDate(inspection, today, "technical inspection");
    }

    private static void requireDate(LocalDate expiry, LocalDate today, String document) {
        if (!LicenseValidity.isValidOn(expiry, today))
            throw new InvalidOperationException("Vehicle " + document + " expiry is missing or expired");
    }
}
