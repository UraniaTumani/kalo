package com.kalo.driver.service;

import com.kalo.assignment.repository.DriverVehicleAssignmentRepository;
import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.dto.CreateDriverRequest;
import com.kalo.driver.dto.DriverResponse;
import com.kalo.driver.dto.UpdateDriverRequest;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kalo.driver.dto.UpdateDriverAvailabilityRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverServiceImpl
        implements DriverService {

    private final DriverRepository driverRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final DriverVehicleAssignmentRepository assignmentRepository;

    @Override
    @Transactional
    public DriverResponse createDriver(
            CreateDriverRequest request
    ) {

        TaxiCompany company = getCurrentCompany();

        validateCompanyCanManageDrivers(company);

        validateLicenseExpiry(
                request.licenseExpiryDate()
        );

        String phone =
                request.phone().trim();

        String licenseNumber =
                request.licenseNumber()
                        .trim()
                        .toUpperCase();

        if (driverRepository.existsByPhone(phone)) {
            throw new ConflictException(
                    "A driver with this phone number already exists"
            );
        }

        if (driverRepository.existsByLicenseNumber(
                licenseNumber
        )) {

            throw new ConflictException(
                    "A driver with this license number already exists"
            );
        }

        Driver driver = new Driver();

        driver.setCompany(company);

        driver.setFirstName(
                request.firstName().trim()
        );

        driver.setLastName(
                request.lastName().trim()
        );

        driver.setPhone(phone);

        driver.setLicenseNumber(
                licenseNumber
        );

        driver.setLicenseExpiryDate(
                request.licenseExpiryDate()
        );

        driver.setDateOfBirth(
                request.dateOfBirth()
        );

        driver.setRating(null);

        driver.setStatus(
                DriverStatus.ACTIVE
        );
        driver.setAvailabilityStatus(
                DriverAvailabilityStatus.OFFLINE
        );
        Driver savedDriver =
                driverRepository.save(driver);

        return mapToResponse(savedDriver);
    }
    @Override
    @Transactional
    public DriverResponse updateAvailability(
            Long driverId,
            UpdateDriverAvailabilityRequest request
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompanyCanManageDrivers(company);

        Driver driver =
                getOwnedDriver(
                        driverId,
                        company.getId()
                );

        if (driver.getStatus()
                != DriverStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Only an active driver can change availability"
            );
        }

        DriverAvailabilityStatus requestedStatus =
                request.availabilityStatus();

        if (requestedStatus
                == DriverAvailabilityStatus.ONLINE) {

            boolean hasActiveVehicle =
                    assignmentRepository
                            .findByDriverIdAndActiveTrue(
                                    driver.getId()
                            )
                            .isPresent();

            if (!hasActiveVehicle) {

                throw new InvalidOperationException(
                        "Driver must have an active vehicle assignment before going online"
                );
            }
        }

        if (requestedStatus
                == DriverAvailabilityStatus.BUSY) {

            throw new InvalidOperationException(
                    "BUSY status cannot be set manually"
            );
        }

        driver.setAvailabilityStatus(
                requestedStatus
        );

        Driver saved =
                driverRepository.save(driver);

        return mapToResponse(saved);
    }
    @Override
    @Transactional(readOnly = true)
    public List<DriverResponse> getDrivers() {

        TaxiCompany company =
                getCurrentCompany();

        return driverRepository
                .findAllByCompanyId(
                        company.getId()
                )
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DriverResponse getDriverById(
            Long driverId
    ) {

        TaxiCompany company =
                getCurrentCompany();

        Driver driver =
                getOwnedDriver(
                        driverId,
                        company.getId()
                );

        return mapToResponse(driver);
    }

    @Override
    @Transactional
    public DriverResponse updateDriver(
            Long driverId,
            UpdateDriverRequest request
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompanyCanManageDrivers(company);

        Driver driver =
                getOwnedDriver(
                        driverId,
                        company.getId()
                );

        validateLicenseExpiry(
                request.licenseExpiryDate()
        );

        String phone =
                request.phone().trim();

        String licenseNumber =
                request.licenseNumber()
                        .trim()
                        .toUpperCase();

        if (!driver.getPhone().equals(phone)
                && driverRepository.existsByPhone(phone)) {

            throw new ConflictException(
                    "A driver with this phone number already exists"
            );
        }

        if (!driver.getLicenseNumber()
                .equals(licenseNumber)
                && driverRepository.existsByLicenseNumber(
                licenseNumber
        )) {

            throw new ConflictException(
                    "A driver with this license number already exists"
            );
        }

        driver.setFirstName(
                request.firstName().trim()
        );

        driver.setLastName(
                request.lastName().trim()
        );

        driver.setPhone(phone);

        driver.setLicenseNumber(
                licenseNumber
        );

        driver.setLicenseExpiryDate(
                request.licenseExpiryDate()
        );

        driver.setDateOfBirth(
                request.dateOfBirth()
        );

        if (request.status() != DriverStatus.ACTIVE) {

            takeOutOfService(driver);
        }

        driver.setStatus(
                request.status()
        );

        Driver savedDriver =
                driverRepository.save(driver);

        return mapToResponse(savedDriver);
    }

    /**
     * Drivers are never removed from the database: historical rides, ratings
     * and assignments reference them. Deletion is a soft deactivation.
     */
    @Override
    @Transactional
    public void deleteDriver(
            Long driverId
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompanyCanManageDrivers(company);

        Driver driver =
                getOwnedDriver(
                        driverId,
                        company.getId()
                );

        takeOutOfService(driver);

        driver.setStatus(
                DriverStatus.INACTIVE
        );

        driverRepository.save(driver);

        log.info(
                "Driver deactivated: driverId={} companyId={}",
                driver.getId(),
                company.getId()
        );
    }

    /**
     * Takes a driver out of service: refuses while they are on a ride, forces
     * them offline and releases the vehicle they were holding.
     */
    private void takeOutOfService(
            Driver driver
    ) {

        if (driver.getAvailabilityStatus()
                == DriverAvailabilityStatus.BUSY) {

            throw new InvalidOperationException(
                    "Driver is currently on a ride and cannot be deactivated"
            );
        }

        driver.setAvailabilityStatus(
                DriverAvailabilityStatus.OFFLINE
        );

        assignmentRepository
                .findByDriverIdAndActiveTrue(driver.getId())
                .ifPresent(assignment -> {

                    assignment.setActive(false);

                    assignment.setAssignedUntil(
                            Instant.now()
                    );

                    assignmentRepository.save(assignment);
                });
    }

    private TaxiCompany getCurrentCompany() {

        String phone = SecurityContextHolder
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

    private Driver getOwnedDriver(
            Long driverId,
            Long companyId
    ) {

        return driverRepository
                .findByIdAndCompanyId(
                        driverId,
                        companyId
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Driver not found"
                        )
                );
    }

    private void validateCompanyCanManageDrivers(
            TaxiCompany company
    ) {

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Only an approved taxi company can manage drivers"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company must be active to manage drivers"
            );
        }
    }

    private void validateLicenseExpiry(
            LocalDate licenseExpiryDate
    ) {

        if (licenseExpiryDate
                .isBefore(LocalDate.now())) {

            throw new InvalidOperationException(
                    "Driver license has expired"
            );
        }
    }

    private DriverResponse mapToResponse(
            Driver driver
    ) {

        return new DriverResponse(
                driver.getId(),
                driver.getFirstName(),
                driver.getLastName(),
                driver.getPhone(),
                driver.getLicenseNumber(),
                driver.getLicenseExpiryDate(),
                driver.getDateOfBirth(),
                driver.getRating(),
                driver.getStatus(),
                driver.getAvailabilityStatus()
        );
    }
}