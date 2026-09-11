package com.kalo.ride.repository;

import com.kalo.driver.entity.Driver;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.vehicle.entity.Vehicle;

/**
 * One online driver with the vehicle they are currently assigned to and their
 * last known position, produced by a single join in
 * {@link TaxiSearchRepository#findAvailableTaxis}.
 */
public record AvailableTaxiRow(

        TaxiCompany company,

        Driver driver,

        Vehicle vehicle,

        Double latitude,

        Double longitude

) {
}
