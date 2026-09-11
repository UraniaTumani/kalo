package com.kalo.location.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
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

        if (driver.getAvailabilityStatus()
                == DriverAvailabilityStatus.OFFLINE) {

            throw new InvalidOperationException(
                    "Offline driver cannot update location"
            );
        }
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