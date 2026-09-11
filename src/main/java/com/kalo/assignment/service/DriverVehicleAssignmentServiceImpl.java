package com.kalo.assignment.service;

import com.kalo.assignment.dto.CreateDriverVehicleAssignmentRequest;
import com.kalo.assignment.dto.DriverVehicleAssignmentResponse;
import com.kalo.assignment.entity.DriverVehicleAssignment;
import com.kalo.assignment.repository.DriverVehicleAssignmentRepository;
import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DriverVehicleAssignmentServiceImpl
        implements DriverVehicleAssignmentService {

    private final DriverVehicleAssignmentRepository assignmentRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;

    @Override
    @Transactional
    public DriverVehicleAssignmentResponse assignVehicle(
            CreateDriverVehicleAssignmentRequest request
    ) {

        TaxiCompany company = getCurrentCompany();

        validateCompanyCanManageAssignments(company);

        Driver driver = driverRepository
                .findByIdAndCompanyId(
                        request.driverId(),
                        company.getId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Driver not found"
                        )
                );

        Vehicle vehicle = vehicleRepository
                .findByIdAndCompanyId(
                        request.vehicleId(),
                        company.getId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Vehicle not found"
                        )
                );

        validateDriver(driver);
        validateVehicle(vehicle);

        /*
         * A driver can have only one active vehicle assignment.
         */
        if (assignmentRepository
                .findByDriverIdAndActiveTrue(
                        driver.getId()
                )
                .isPresent()) {

            throw new ConflictException(
                    "Driver already has an active vehicle assignment"
            );
        }

        /*
         * A vehicle can be assigned to only one active driver.
         */
        if (assignmentRepository
                .findByVehicleIdAndActiveTrue(
                        vehicle.getId()
                )
                .isPresent()) {

            throw new ConflictException(
                    "Vehicle is already assigned to another driver"
            );
        }

        Instant now = Instant.now();

        DriverVehicleAssignment assignment =
                new DriverVehicleAssignment();

        assignment.setDriver(driver);
        assignment.setVehicle(vehicle);

        assignment.setAssignedFrom(now);
        assignment.setAssignedUntil(null);

        assignment.setActive(true);

        DriverVehicleAssignment savedAssignment =
                assignmentRepository.save(
                        assignment
                );

        return mapToResponse(
                savedAssignment
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<DriverVehicleAssignmentResponse> getAssignments() {

        TaxiCompany company =
                getCurrentCompany();

        return assignmentRepository
                .findAllByDriverCompanyId(
                        company.getId()
                )
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void unassignVehicle(
            Long assignmentId
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompanyCanManageAssignments(
                company
        );

        DriverVehicleAssignment assignment =
                assignmentRepository
                        .findByIdAndDriverCompanyId(
                                assignmentId,
                                company.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Driver vehicle assignment not found"
                                )
                        );

        if (!assignment.isActive()) {

            throw new InvalidOperationException(
                    "Driver vehicle assignment is already inactive"
            );
        }

        Driver driver =
                assignment.getDriver();

        /*
         * We do not automatically change the driver's
         * availability here.
         *
         * Before changing vehicle assignment,
         * the driver must explicitly be OFFLINE.
         */
        if (driver.getAvailabilityStatus()
                != DriverAvailabilityStatus.OFFLINE) {

            throw new InvalidOperationException(
                    "Driver must be offline before vehicle can be unassigned"
            );
        }

        Instant now =
                Instant.now();

        assignment.setActive(false);

        assignment.setAssignedUntil(
                now
        );

        assignmentRepository.save(
                assignment
        );
    }

    /**
     * Returns the taxi company belonging
     * to the currently authenticated partner.
     */
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

    /**
     * Only an approved and active taxi company
     * can manage driver/vehicle assignments.
     */
    private void validateCompanyCanManageAssignments(
            TaxiCompany company
    ) {

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Only an approved taxi company can manage assignments"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company must be active to manage assignments"
            );
        }
    }

    /**
     * Driver must:
     *
     * 1. Be ACTIVE administratively.
     * 2. Be OFFLINE operationally.
     *
     * This prevents changing vehicle assignment
     * while the driver is available or working.
     */
    private void validateDriver(
            Driver driver
    ) {

        if (driver.getStatus()
                != DriverStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Only an active driver can receive a vehicle"
            );
        }

        if (driver.getAvailabilityStatus()
                != DriverAvailabilityStatus.OFFLINE) {

            throw new InvalidOperationException(
                    "Driver must be offline before assigning a vehicle"
            );
        }
    }

    /**
     * Only an ACTIVE vehicle can be assigned.
     */
    private void validateVehicle(
            Vehicle vehicle
    ) {

        if (vehicle.getStatus()
                != VehicleStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Only an active vehicle can be assigned"
            );
        }
    }

    /**
     * Converts entity to API response DTO.
     */
    private DriverVehicleAssignmentResponse mapToResponse(
            DriverVehicleAssignment assignment
    ) {

        Driver driver =
                assignment.getDriver();

        Vehicle vehicle =
                assignment.getVehicle();

        String driverName =
                driver.getFirstName()
                        + " "
                        + driver.getLastName();

        String vehicleDescription =
                vehicle.getBrand()
                        + " "
                        + vehicle.getModel();

        return new DriverVehicleAssignmentResponse(
                assignment.getId(),

                driver.getId(),
                driverName,

                vehicle.getId(),
                vehicle.getPlateNumber(),
                vehicleDescription,

                assignment.getAssignedFrom(),
                assignment.getAssignedUntil(),

                assignment.isActive()
        );
    }
}