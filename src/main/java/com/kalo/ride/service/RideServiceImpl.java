package com.kalo.ride.service;

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
import com.kalo.partner.service.CompanyAvailabilityChecker;
import com.kalo.ride.dto.AcceptRideRequest;
import com.kalo.ride.dto.CompleteRideRequest;
import com.kalo.ride.dto.PartnerRideResponse;
import com.kalo.ride.dto.RideResponse;
import com.kalo.ride.dto.SelectTaxiOfferRequest;
import com.kalo.ride.entity.Ride;
import com.kalo.ride.entity.RideOffer;
import com.kalo.ride.entity.RideRequest;
import com.kalo.ride.enums.RideRequestStatus;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideOfferRepository;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.repository.RideRequestRepository;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RideServiceImpl implements RideService {

    private final RideRequestRepository rideRequestRepository;
    private final RideOfferRepository rideOfferRepository;
    private final RideRepository rideRepository;
    private final UserRepository userRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final DriverRepository driverRepository;
    private final DriverVehicleAssignmentRepository assignmentRepository;
    private final CompanyAvailabilityChecker companyAvailabilityChecker;

    /*
     * =========================================================
     * CUSTOMER SELECTS TAXI COMPANY
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse selectTaxiOffer(
            Long rideRequestId,
            SelectTaxiOfferRequest request
    ) {

        User customer =
                getCurrentCustomer();

        List<RideStatus> activeStatuses =
                List.of(
                        RideStatus.REQUESTED,
                        RideStatus.ACCEPTED,
                        RideStatus.DRIVER_ASSIGNED,
                        RideStatus.DRIVER_ARRIVING,
                        RideStatus.DRIVER_ARRIVED,
                        RideStatus.IN_PROGRESS
                );

        if (rideRepository
                .existsByCustomerIdAndStatusIn(
                        customer.getId(),
                        activeStatuses
                )) {

            throw new ConflictException(
                    "Customer already has an active ride"
            );
        }

        RideRequest rideRequest =
                rideRequestRepository
                        .findByIdAndCustomerId(
                                rideRequestId,
                                customer.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Ride request not found"
                                )
                        );

        if (rideRequest.getStatus()
                != RideRequestStatus.SEARCHING) {

            throw new InvalidOperationException(
                    "Ride request is not available for taxi selection"
            );
        }

        Instant now =
                Instant.now();

        if (!rideRequest.getExpiresAt()
                .isAfter(now)) {

            rideRequest.setStatus(
                    RideRequestStatus.EXPIRED
            );

            rideRequestRepository.save(
                    rideRequest
            );

            throw new InvalidOperationException(
                    "Ride request has expired"
            );
        }

        RideOffer offer =
                rideOfferRepository
                        .findByIdAndRideRequestIdAndRideRequestCustomerId(
                                request.offerId(),
                                rideRequest.getId(),
                                customer.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Taxi offer not found"
                                )
                        );

        TaxiCompany company =
                offer.getCompany();

        /*
         * Revalidate company now.
         *
         * The company may have been available when the
         * search was created but could have changed:
         *
         * - booking enabled
         * - payment methods
         * - operating hours
         * - service area
         */
        validateCompany(
                company,
                rideRequest,
                now
        );

        if (rideRepository
                .existsByRideRequestIdAndCompanyId(
                        rideRequest.getId(),
                        company.getId()
                )) {

            throw new ConflictException(
                    "This taxi company has already been tried for this ride request"
            );
        }

        Ride ride =
                new Ride();

        ride.setRideRequest(
                rideRequest
        );

        ride.setCustomer(
                customer
        );

        ride.setCompany(
                company
        );

        /*
         * Driver and vehicle are assigned later
         * when the partner accepts the ride.
         */
        ride.setDriver(
                null
        );

        ride.setVehicle(
                null
        );

        ride.setStatus(
                RideStatus.REQUESTED
        );

        ride.setRequestedAt(
                now
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        rideRequest.setStatus(
                RideRequestStatus.SELECTED
        );

        rideRequestRepository.save(
                rideRequest
        );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * PARTNER ACCEPTS RIDE AND ASSIGNS DRIVER
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse acceptRide(
            Long rideId,
            AcceptRideRequest request
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        Ride ride =
                getOwnedRideForUpdate(
                        rideId,
                        company.getId()
                );

        if (ride.getStatus()
                != RideStatus.REQUESTED) {

            throw new InvalidOperationException(
                    "Only a requested ride can be accepted"
            );
        }

        Driver driver =
                driverRepository
                        .findForUpdateByIdAndCompanyId(
                                request.driverId(),
                                company.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Driver not found"
                                )
                        );

        if (driver.getStatus()
                != DriverStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Only an active driver can be assigned to a ride"
            );
        }

        if (driver.getAvailabilityStatus()
                != DriverAvailabilityStatus.ONLINE) {

            throw new InvalidOperationException(
                    "Driver must be online and available"
            );
        }

        DriverVehicleAssignment assignment =
                assignmentRepository
                        .findActiveAssignmentWithVehicle(
                                driver.getId()
                        )
                        .orElseThrow(() ->
                                new InvalidOperationException(
                                        "Driver does not have an active vehicle assignment"
                                )
                        );

        Vehicle vehicle =
                assignment.getVehicle();

        if (!vehicle.getCompany()
                .getId()
                .equals(company.getId())) {

            throw new InvalidOperationException(
                    "Assigned vehicle does not belong to this taxi company"
            );
        }

        if (vehicle.getStatus()
                != VehicleStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Assigned vehicle is not active"
            );
        }

        ride.setDriver(
                driver
        );

        ride.setVehicle(
                vehicle
        );

        ride.setAcceptedAt(
                Instant.now()
        );

        ride.setStatus(
                RideStatus.DRIVER_ASSIGNED
        );

        driver.setAvailabilityStatus(
                DriverAvailabilityStatus.BUSY
        );

        driverRepository.save(
                driver
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * PARTNER DECLINES RIDE
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse declineRide(
            Long rideId
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        Ride ride =
                getOwnedRideForUpdate(
                        rideId,
                        company.getId()
                );

        if (ride.getStatus()
                != RideStatus.REQUESTED) {

            throw new InvalidOperationException(
                    "Only a requested ride can be declined"
            );
        }

        Instant now =
                Instant.now();

        ride.setStatus(
                RideStatus.DECLINED
        );

        ride.setDeclinedAt(
                now
        );

        RideRequest rideRequest =
                ride.getRideRequest();

        if (rideRequest.getExpiresAt()
                .isAfter(now)) {

            rideRequest.setStatus(
                    RideRequestStatus.SEARCHING
            );

        } else {

            rideRequest.setStatus(
                    RideRequestStatus.EXPIRED
            );
        }

        rideRequestRepository.save(
                rideRequest
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * DRIVER STARTS TRAVELLING TO CUSTOMER
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse markDriverArriving(
            Long rideId
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        Ride ride =
                getOwnedRideForUpdate(
                        rideId,
                        company.getId()
                );

        if (ride.getStatus()
                != RideStatus.DRIVER_ASSIGNED) {

            throw new InvalidOperationException(
                    "Ride must be driver assigned before driver can start arriving"
            );
        }

        ride.setStatus(
                RideStatus.DRIVER_ARRIVING
        );

        ride.setDriverArrivingAt(
                Instant.now()
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * DRIVER ARRIVED
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse markDriverArrived(
            Long rideId
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        Ride ride =
                getOwnedRideForUpdate(
                        rideId,
                        company.getId()
                );

        if (ride.getStatus()
                != RideStatus.DRIVER_ARRIVING) {

            throw new InvalidOperationException(
                    "Ride must be driver arriving before marking driver as arrived"
            );
        }

        ride.setStatus(
                RideStatus.DRIVER_ARRIVED
        );

        ride.setDriverArrivedAt(
                Instant.now()
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * START RIDE
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse startRide(
            Long rideId
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        Ride ride =
                getOwnedRideForUpdate(
                        rideId,
                        company.getId()
                );

        if (ride.getStatus()
                != RideStatus.DRIVER_ARRIVED) {

            throw new InvalidOperationException(
                    "Ride can only start after driver has arrived"
            );
        }

        ride.setStatus(
                RideStatus.IN_PROGRESS
        );

        ride.setStartedAt(
                Instant.now()
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * COMPLETE RIDE
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse completeRide(
            Long rideId,
            CompleteRideRequest request
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        Ride ride =
                getOwnedRideForUpdate(
                        rideId,
                        company.getId()
                );

        if (ride.getStatus()
                != RideStatus.IN_PROGRESS) {

            throw new InvalidOperationException(
                    "Only a ride in progress can be completed"
            );
        }

        if (ride.getDriver() == null) {

            throw new InvalidOperationException(
                    "Ride does not have an assigned driver"
            );
        }

        Driver driver =
                driverRepository
                        .findForUpdateByIdAndCompanyId(
                                ride.getDriver().getId(),
                                company.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Driver not found"
                                )
                        );

        Instant now =
                Instant.now();

        ride.setFinalAmount(
                request.finalAmount()
        );

        ride.setCompletedAt(
                now
        );

        ride.setStatus(
                RideStatus.COMPLETED
        );

        RideRequest rideRequest =
                ride.getRideRequest();

        /*
         * This search successfully generated
         * a completed ride.
         */
        rideRequest.setStatus(
                RideRequestStatus.SELECTED
        );

        rideRequestRepository.save(
                rideRequest
        );

        /*
         * Driver becomes available again.
         */
        driver.setAvailabilityStatus(
                DriverAvailabilityStatus.ONLINE
        );

        driverRepository.save(
                driver
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * CUSTOMER CURRENT RIDE
     * =========================================================
     */

    @Override
    @Transactional(readOnly = true)
    public RideResponse getCurrentRide() {

        User customer =
                getCurrentCustomer();

        List<RideStatus> activeStatuses =
                List.of(
                        RideStatus.REQUESTED,
                        RideStatus.ACCEPTED,
                        RideStatus.DRIVER_ASSIGNED,
                        RideStatus.DRIVER_ARRIVING,
                        RideStatus.DRIVER_ARRIVED,
                        RideStatus.IN_PROGRESS
                );

        Ride ride =
                rideRepository
                        .findFirstByCustomerIdAndStatusInOrderByRequestedAtDesc(
                                customer.getId(),
                                activeStatuses
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "No active ride found"
                                )
                        );

        return mapToResponse(
                ride
        );
    }

    /*
     * =========================================================
     * CUSTOMER RIDE HISTORY
     * =========================================================
     */

    @Override
    @Transactional(readOnly = true)
    public List<RideResponse> getRideHistory() {

        User customer =
                getCurrentCustomer();

        return rideRepository
                .findAllByCustomerIdOrderByRequestedAtDesc(
                        customer.getId()
                )
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    /*
     * =========================================================
     * CUSTOMER RIDE DETAIL
     * =========================================================
     */

    @Override
    @Transactional(readOnly = true)
    public RideResponse getRideById(
            Long rideId
    ) {

        User customer =
                getCurrentCustomer();

        Ride ride =
                rideRepository
                        .findByIdAndCustomerId(
                                rideId,
                                customer.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Ride not found"
                                )
                        );

        return mapToResponse(
                ride
        );
    }

    /*
     * =========================================================
     * CUSTOMER CANCELS RIDE
     * =========================================================
     */

    @Override
    @Transactional
    public RideResponse cancelRide(
            Long rideId
    ) {

        User customer =
                getCurrentCustomer();

        Ride ride =
                rideRepository
                        .findByIdAndCustomerId(
                                rideId,
                                customer.getId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Ride not found"
                                )
                        );

        RideStatus currentStatus =
                ride.getStatus();

        if (currentStatus == RideStatus.IN_PROGRESS) {

            throw new InvalidOperationException(
                    "Ride cannot be cancelled after it has started"
            );
        }

        if (currentStatus == RideStatus.COMPLETED) {

            throw new InvalidOperationException(
                    "Completed ride cannot be cancelled"
            );
        }

        if (currentStatus == RideStatus.CANCELLED) {

            throw new InvalidOperationException(
                    "Ride is already cancelled"
            );
        }

        if (currentStatus == RideStatus.DECLINED) {

            throw new InvalidOperationException(
                    "Declined ride cannot be cancelled"
            );
        }

        if (currentStatus == RideStatus.NO_RESPONSE) {

            throw new InvalidOperationException(
                    "Ride is no longer active"
            );
        }

        /*
         * Release assigned driver if needed.
         */
        if (ride.getDriver() != null) {

            Driver driver =
                    driverRepository
                            .findForUpdateByIdAndCompanyId(
                                    ride.getDriver().getId(),
                                    ride.getCompany().getId()
                            )
                            .orElseThrow(() ->
                                    new ResourceNotFoundException(
                                            "Driver not found"
                                    )
                            );

            if (driver.getAvailabilityStatus()
                    == DriverAvailabilityStatus.BUSY) {

                driver.setAvailabilityStatus(
                        DriverAvailabilityStatus.ONLINE
                );

                driverRepository.save(
                        driver
                );
            }
        }

        ride.setStatus(
                RideStatus.CANCELLED
        );

        ride.setCancelledAt(
                Instant.now()
        );

        RideRequest rideRequest =
                ride.getRideRequest();

        /*
         * Customer explicitly cancelled the trip,
         * therefore this search is finished.
         */
        rideRequest.setStatus(
                RideRequestStatus.CANCELLED
        );

        rideRequestRepository.save(
                rideRequest
        );

        Ride savedRide =
                rideRepository.save(
                        ride
                );

        return mapToResponse(
                savedRide
        );
    }

    /*
     * =========================================================
     * PARTNER LIST OF RIDES
     * =========================================================
     */

    @Override
    @Transactional(readOnly = true)
    public List<PartnerRideResponse> getPartnerRides(
            RideStatus status
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        List<Ride> rides;

        if (status == null) {

            rides =
                    rideRepository
                            .findAllByCompanyId(
                                    company.getId()
                            );

        } else {

            rides =
                    rideRepository
                            .findAllByCompanyIdAndStatus(
                                    company.getId(),
                                    status
                            );
        }

        return rides
                .stream()
                .map(this::mapToPartnerResponse)
                .toList();
    }

    /*
     * =========================================================
     * CURRENT CUSTOMER
     * =========================================================
     */

    private User getCurrentCustomer() {

        String phone =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        User user =
                userRepository
                        .findByPhone(
                                phone
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "User not found"
                                )
                        );

        if (user.getRole()
                != UserRole.CUSTOMER) {

            throw new InvalidOperationException(
                    "Only customers can perform this operation"
            );
        }

        if (user.getStatus()
                != UserStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Customer account is not active"
            );
        }

        return user;
    }

    /*
     * =========================================================
     * CURRENT PARTNER COMPANY
     * =========================================================
     */

    private TaxiCompany getCurrentPartnerCompany() {

        String phone =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        TaxiCompany company =
                taxiCompanyRepository
                        .findByOwnerPhone(
                                phone
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Taxi company not found for current partner"
                                )
                        );

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Only an approved taxi company can manage rides"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company must be active to manage rides"
            );
        }

        return company;
    }

    /*
     * =========================================================
     * LOAD PARTNER RIDE WITH DATABASE LOCK
     * =========================================================
     */

    private Ride getOwnedRideForUpdate(
            Long rideId,
            Long companyId
    ) {

        return rideRepository
                .findForUpdateByIdAndCompanyId(
                        rideId,
                        companyId
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Ride not found"
                        )
                );
    }

    /*
     * =========================================================
     * COMPANY VALIDATION
     * =========================================================
     */

    private void validateCompany(
            TaxiCompany company,
            RideRequest rideRequest,
            Instant now
    ) {

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Taxi company is no longer available"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company is no longer available"
            );
        }

        if (!company.isBookingEnabled()) {

            throw new InvalidOperationException(
                    "Taxi company is not currently accepting bookings"
            );
        }

        if (company.getPaymentMethods() == null
                || company.getPaymentMethods().isEmpty()) {

            throw new InvalidOperationException(
                    "Taxi company does not have an available payment method"
            );
        }

        /*
         * Recheck operating hours + service area.
         *
         * This is important because the offer may have
         * been generated a few minutes earlier.
         */
        boolean available =
                companyAvailabilityChecker.isAvailable(
                        company,
                        rideRequest.getPickupLatitude(),
                        rideRequest.getPickupLongitude(),
                        now
                );

        if (!available) {

            throw new InvalidOperationException(
                    "Taxi company is not currently available for this pickup location"
            );
        }
    }

    /*
     * =========================================================
     * CUSTOMER RESPONSE MAPPER
     * =========================================================
     */

    private RideResponse mapToResponse(
            Ride ride
    ) {

        RideRequest rideRequest =
                ride.getRideRequest();

        return new RideResponse(
                ride.getId(),
                rideRequest.getId(),
                ride.getCompany().getId(),
                ride.getCompany().getDisplayName(),

                ride.getDriver() == null
                        ? null
                        : ride.getDriver().getId(),

                ride.getVehicle() == null
                        ? null
                        : ride.getVehicle().getId(),

                ride.getStatus(),
                rideRequest.getPickupAddress(),
                rideRequest.getDestinationAddress(),
                ride.getRequestedAt(),
                ride.getAcceptedAt(),
                ride.getDriverArrivingAt(),
                ride.getDriverArrivedAt(),
                ride.getStartedAt(),
                ride.getCompletedAt(),
                ride.getFinalAmount()
        );
    }

    /*
     * =========================================================
     * PARTNER RESPONSE MAPPER
     * =========================================================
     */

    private PartnerRideResponse mapToPartnerResponse(
            Ride ride
    ) {

        User customer =
                ride.getCustomer();

        RideRequest rideRequest =
                ride.getRideRequest();

        return new PartnerRideResponse(
                ride.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getPhone(),
                rideRequest.getPickupLatitude(),
                rideRequest.getPickupLongitude(),
                rideRequest.getPickupAddress(),
                rideRequest.getDestinationLatitude(),
                rideRequest.getDestinationLongitude(),
                rideRequest.getDestinationAddress(),
                ride.getStatus(),
                ride.getRequestedAt()
        );
    }
}