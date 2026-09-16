package com.kalo.location.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.location.dto.DriverLocationResponse;
import com.kalo.location.dto.UpdateDriverLocationRequest;
import com.kalo.location.entity.DriverLocation;
import com.kalo.location.repository.DriverLocationRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class DriverLocationServiceImpl
        implements DriverLocationService {

    private final DriverLocationRepository driverLocationRepository;
    private final DriverRepository driverRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;

    @Override
    @Transactional
    public DriverLocationResponse updateLocation(
            Long driverId,
            UpdateDriverLocationRequest request
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompany(company);

        Driver driver =
                driverRepository
                        .findByIdAndCompanyId(
                                driverId,
                                company.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Driver not found"
                                )
                        );

        validateDriverCanSendLocation(driver);

        DriverLocation location =
                driverLocationRepository
                        .findByDriverId(driver.getId())
                        .orElseGet(() -> {

                            DriverLocation newLocation =
                                    new DriverLocation();

                            newLocation.setDriver(driver);

                            return newLocation;
                        });

        location.setLatitude(
                request.latitude()
        );

        location.setLongitude(
                request.longitude()
        );

        location.setLocationUpdatedAt(
                Instant.now()
        );

        DriverLocation saved =
                driverLocationRepository.save(location);

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DriverLocationResponse getLocation(
            Long driverId
    ) {

        TaxiCompany company =
                getCurrentCompany();

        DriverLocation location =
                driverLocationRepository
                        .findByDriverIdAndDriverCompanyId(
                                driverId,
                                company.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Driver location not found"
                                )
                        );

        return mapToResponse(location);
    }

    private TaxiCompany getCurrentCompany() {

        String phone =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        return taxiCompanyRepository
                .findByOwnerPhone(phone)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Taxi company not found for current partner"
                        )
                );
    }

    private void validateCompany(
            TaxiCompany company
    ) {

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Only an approved taxi company can update driver locations"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company must be active"
            );
        }
    }

    private void validateDriverCanSendLocation(
            Driver driver
    ) {

        if (driver.getStatus()
                != DriverStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Only an active driver can update location"
            );
        }

        /*
         * An OFFLINE driver may report a position.
         *
         * This used to be refused, which broke the only way a partner puts
         * somebody on the road. Going online needs a fresh position — a driver
         * with a stale one is not offered to passengers — so the screen asks
         * for GPS, sends the position, and only then flips availability. With
         * the old rule that first call came back 400 "Offline driver cannot
         * update location", the request threw before availability was ever
         * touched, and the driver stayed offline. An offline fleet takes no
         * rides at all.
         *
         * Reporting a position is a statement about where someone is, not a
         * claim to be dispatchable, and nothing treats it as one: ride search
         * filters on ONLINE, on an active vehicle assignment and on the age of
         * the fix before a driver is ever offered. A position from an offline
         * driver is simply a position.
         *
         * Only DriverStatus is still checked. A deactivated driver has no
         * business reporting anything.
         */
    }

    private DriverLocationResponse mapToResponse(
            DriverLocation location
    ) {

        return new DriverLocationResponse(
                location.getDriver().getId(),
                location.getLatitude(),
                location.getLongitude(),
                location.getLocationUpdatedAt()
        );
    }
}