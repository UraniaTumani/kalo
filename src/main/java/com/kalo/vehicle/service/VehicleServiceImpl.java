package com.kalo.vehicle.service;

import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.vehicle.dto.CreateVehicleRequest;
import com.kalo.vehicle.dto.UpdateVehicleRequest;
import com.kalo.vehicle.dto.VehicleResponse;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleServiceImpl
        implements VehicleService {

    private final VehicleRepository vehicleRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;

    @Override
    @Transactional
    public VehicleResponse createVehicle(
            CreateVehicleRequest request
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompanyCanManageVehicles(company);

        validateManufactureYear(
                request.manufactureYear()
        );

        validateExpiryDates(
                request.registrationExpiryDate(),
                request.insuranceExpiryDate(),
                request.technicalInspectionExpiryDate()
        );

        String plateNumber =
                normalizePlateNumber(
                        request.plateNumber()
                );

        if (vehicleRepository
                .existsByPlateNumber(plateNumber)) {

            throw new ConflictException(
                    "A vehicle with this plate number already exists"
            );
        }

        Vehicle vehicle = new Vehicle();

        vehicle.setCompany(company);
        vehicle.setPlateNumber(plateNumber);

        vehicle.setBrand(
                request.brand().trim()
        );

        vehicle.setModel(
                request.model().trim()
        );

        vehicle.setManufactureYear(
                request.manufactureYear()
        );

        vehicle.setSeats(
                request.seats()
        );

        vehicle.setVehicleType(
                request.vehicleType()
        );

        vehicle.setRegistrationExpiryDate(
                request.registrationExpiryDate()
        );

        vehicle.setInsuranceExpiryDate(
                request.insuranceExpiryDate()
        );

        vehicle.setTechnicalInspectionExpiryDate(
                request.technicalInspectionExpiryDate()
        );

        vehicle.setStatus(
                VehicleStatus.ACTIVE
        );

        Vehicle savedVehicle =
                vehicleRepository.save(vehicle);

        return mapToResponse(savedVehicle);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VehicleResponse> getVehicles() {

        TaxiCompany company =
                getCurrentCompany();

        return vehicleRepository
                .findAllByCompanyId(
                        company.getId()
                )
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VehicleResponse getVehicleById(
            Long vehicleId
    ) {

        TaxiCompany company =
                getCurrentCompany();

        Vehicle vehicle =
                getOwnedVehicle(
                        vehicleId,
                        company.getId()
                );

        return mapToResponse(vehicle);
    }

    @Override
    @Transactional
    public VehicleResponse updateVehicle(
            Long vehicleId,
            UpdateVehicleRequest request
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompanyCanManageVehicles(company);

        Vehicle vehicle =
                getOwnedVehicle(
                        vehicleId,
                        company.getId()
                );

        validateManufactureYear(
                request.manufactureYear()
        );

        validateExpiryDates(
                request.registrationExpiryDate(),
                request.insuranceExpiryDate(),
                request.technicalInspectionExpiryDate()
        );

        String plateNumber =
                normalizePlateNumber(
                        request.plateNumber()
                );

        if (!vehicle.getPlateNumber()
                .equals(plateNumber)
                && vehicleRepository
                .existsByPlateNumber(plateNumber)) {

            throw new ConflictException(
                    "A vehicle with this plate number already exists"
            );
        }

        vehicle.setPlateNumber(
                plateNumber
        );

        vehicle.setBrand(
                request.brand().trim()
        );

        vehicle.setModel(
                request.model().trim()
        );

        vehicle.setManufactureYear(
                request.manufactureYear()
        );

        vehicle.setSeats(
                request.seats()
        );

        vehicle.setVehicleType(
                request.vehicleType()
        );

        vehicle.setRegistrationExpiryDate(
                request.registrationExpiryDate()
        );

        vehicle.setInsuranceExpiryDate(
                request.insuranceExpiryDate()
        );

        vehicle.setTechnicalInspectionExpiryDate(
                request.technicalInspectionExpiryDate()
        );

        vehicle.setStatus(
                request.status()
        );

        Vehicle savedVehicle =
                vehicleRepository.save(vehicle);

        return mapToResponse(savedVehicle);
    }

    @Override
    @Transactional
    public void deleteVehicle(
            Long vehicleId
    ) {

        TaxiCompany company =
                getCurrentCompany();

        validateCompanyCanManageVehicles(company);

        Vehicle vehicle =
                getOwnedVehicle(
                        vehicleId,
                        company.getId()
                );

        vehicleRepository.delete(vehicle);
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

    private Vehicle getOwnedVehicle(
            Long vehicleId,
            Long companyId
    ) {

        return vehicleRepository
                .findByIdAndCompanyId(
                        vehicleId,
                        companyId
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Vehicle not found"
                        )
                );
    }

    private void validateCompanyCanManageVehicles(
            TaxiCompany company
    ) {

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Only an approved taxi company can manage vehicles"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company must be active to manage vehicles"
            );
        }
    }

    private void validateManufactureYear(
            Integer manufactureYear
    ) {

        int currentYear =
                Year.now().getValue();

        if (manufactureYear > currentYear + 1) {

            throw new InvalidOperationException(
                    "Vehicle manufacture year is not valid"
            );
        }
    }

    private void validateExpiryDates(
            LocalDate registrationExpiryDate,
            LocalDate insuranceExpiryDate,
            LocalDate technicalInspectionExpiryDate
    ) {

        LocalDate today = LocalDate.now();

        if (registrationExpiryDate != null
                && registrationExpiryDate.isBefore(today)) {

            throw new InvalidOperationException(
                    "Vehicle registration has expired"
            );
        }

        if (insuranceExpiryDate != null
                && insuranceExpiryDate.isBefore(today)) {

            throw new InvalidOperationException(
                    "Vehicle insurance has expired"
            );
        }

        if (technicalInspectionExpiryDate != null
                && technicalInspectionExpiryDate.isBefore(today)) {

            throw new InvalidOperationException(
                    "Vehicle technical inspection has expired"
            );
        }
    }

    private String normalizePlateNumber(
            String plateNumber
    ) {

        return plateNumber
                .trim()
                .replaceAll("\\s+", "")
                .toUpperCase();
    }

    private VehicleResponse mapToResponse(
            Vehicle vehicle
    ) {

        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getPlateNumber(),
                vehicle.getBrand(),
                vehicle.getModel(),
                vehicle.getManufactureYear(),
                vehicle.getSeats(),
                vehicle.getVehicleType(),
                vehicle.getRegistrationExpiryDate(),
                vehicle.getInsuranceExpiryDate(),
                vehicle.getTechnicalInspectionExpiryDate(),
                vehicle.getStatus()
        );
    }
}