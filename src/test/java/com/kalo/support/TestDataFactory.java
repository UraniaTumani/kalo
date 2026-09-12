package com.kalo.support;

import com.kalo.assignment.entity.DriverVehicleAssignment;
import com.kalo.assignment.repository.DriverVehicleAssignmentRepository;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.enums.DriverStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.location.entity.DriverLocation;
import com.kalo.location.repository.DriverLocationRepository;
import com.kalo.partner.entity.CompanyOperatingHours;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.PaymentMethod;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.CompanyOperatingHoursRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.user.repository.UserRepository;
import com.kalo.vehicle.entity.Vehicle;
import com.kalo.vehicle.enums.VehicleStatus;
import com.kalo.vehicle.enums.VehicleType;
import com.kalo.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the rows a test needs, straight through the repositories.
 *
 * Tests describe the state they care about and let this fill in everything
 * else, so a search test does not have to spell out licence expiry dates to
 * get a valid company on the road.
 */
@Component
@RequiredArgsConstructor
public class TestDataFactory {

    public static final String PASSWORD = "TestPass123!";

    public static final double TIRANA_LAT = 41.3275;
    public static final double TIRANA_LNG = 19.8187;

    /** Keeps phone numbers and plates unique without tests having to care. */
    private final AtomicInteger sequence = new AtomicInteger(1000);

    private final UserRepository userRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final CompanyOperatingHoursRepository operatingHoursRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverVehicleAssignmentRepository assignmentRepository;
    private final DriverLocationRepository driverLocationRepository;
    private final DocumentRepository documentRepository;
    private final PasswordEncoder passwordEncoder;

    public int next() {
        return sequence.incrementAndGet();
    }

    public String nextPhone() {
        return "+3556900" + next();
    }

    /* ------------------------------------------------------------- users */

    public User customer() {
        return user(UserRole.CUSTOMER, UserStatus.ACTIVE);
    }

    public User admin() {
        return user(UserRole.ADMIN, UserStatus.ACTIVE);
    }

    public User user(UserRole role, UserStatus status) {

        int id = next();

        User user = new User();
        user.setFirstName("Test");
        user.setLastName("User" + id);
        user.setPhone("+3556900" + id);
        user.setEmail("user" + id + "@kalo.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(status);
        user.setPhoneVerified(true);

        return userRepository.save(user);
    }

    /* --------------------------------------------------------- companies */

    /** A company that passes every availability rule. */
    public TaxiCompany approvedCompany() {
        return company(
                VerificationStatus.APPROVED,
                CompanyStatus.ACTIVE,
                true,
                Set.of(PaymentMethod.CASH)
        );
    }

    public TaxiCompany company(
            VerificationStatus verificationStatus,
            CompanyStatus status,
            boolean bookingEnabled,
            Set<PaymentMethod> paymentMethods
    ) {

        int id = next();
        User owner = user(UserRole.PARTNER, UserStatus.ACTIVE);

        TaxiCompany company = new TaxiCompany();
        company.setOwner(owner);
        company.setLegalName("Test Taxi " + id + " SHPK");
        company.setDisplayName("Test Taxi " + id);
        company.setNipt("K" + id + "A");
        company.setPhone(owner.getPhone());
        company.setEmail("company" + id + "@kalo.test");
        company.setAddress("Rruga Test " + id + ", Tirane");
        company.setLicenseNumber("TAXI-" + id);
        company.setLicenseExpiryDate(LocalDate.now().plusYears(2));
        company.setVerificationStatus(verificationStatus);
        company.setStatus(status);
        company.setBookingEnabled(bookingEnabled);
        company.setPaymentMethods(new HashSet<>(paymentMethods));
        company.setTimezone("Europe/Tirane");

        TaxiCompany saved = taxiCompanyRepository.save(company);

        openAllWeek(saved);

        return saved;
    }

    /** Open around the clock, so time of day never makes a test flaky. */
    public void openAllWeek(TaxiCompany company) {

        for (DayOfWeek day : DayOfWeek.values()) {

            CompanyOperatingHours hours = new CompanyOperatingHours();
            hours.setCompany(company);
            hours.setDayOfWeek(day);
            hours.setOpenTime(LocalTime.MIDNIGHT);
            hours.setCloseTime(LocalTime.MIDNIGHT);
            hours.setClosed(false);

            operatingHoursRepository.save(hours);
        }
    }

    public void closeAllWeek(TaxiCompany company) {

        operatingHoursRepository.deleteAll(
                operatingHoursRepository.findAllByCompanyIdOrderByDayOfWeek(company.getId())
        );

        for (DayOfWeek day : DayOfWeek.values()) {

            CompanyOperatingHours hours = new CompanyOperatingHours();
            hours.setCompany(company);
            hours.setDayOfWeek(day);
            hours.setOpenTime(LocalTime.MIDNIGHT);
            hours.setCloseTime(LocalTime.MIDNIGHT);
            hours.setClosed(true);

            operatingHoursRepository.save(hours);
        }
    }

    public Document companyDocument(
            TaxiCompany company,
            DocumentType type,
            DocumentVerificationStatus status
    ) {

        Document document = new Document();
        document.setOwnerType(DocumentOwnerType.COMPANY);
        document.setOwnerId(company.getId());
        document.setDocumentType(type);
        document.setDocumentNumber("DOC-" + next());
        document.setFileUrl("https://example.test/doc-" + next() + ".pdf");
        document.setIssuedAt(LocalDate.now().minusYears(1));
        document.setExpiresAt(LocalDate.now().plusYears(2));
        document.setVerificationStatus(status);

        return documentRepository.save(document);
    }

    /* ------------------------------------------------------------ fleet */

    public Driver driver(TaxiCompany company, DriverStatus status, DriverAvailabilityStatus availability) {

        int id = next();

        Driver driver = new Driver();
        driver.setCompany(company);
        driver.setFirstName("Driver");
        driver.setLastName("Number" + id);
        driver.setPhone("+3556910" + id);
        driver.setLicenseNumber("DRV-" + id);
        driver.setLicenseExpiryDate(LocalDate.now().plusYears(3));
        driver.setDateOfBirth(LocalDate.of(1990, 1, 1));
        driver.setStatus(status);
        driver.setAvailabilityStatus(availability);

        return driverRepository.save(driver);
    }

    public Vehicle vehicle(TaxiCompany company, VehicleStatus status) {

        int id = next();

        Vehicle vehicle = new Vehicle();
        vehicle.setCompany(company);
        vehicle.setPlateNumber("AA" + id + "TR");
        vehicle.setBrand("Skoda");
        vehicle.setModel("Octavia");
        vehicle.setManufactureYear(2021);
        vehicle.setSeats(4);
        vehicle.setVehicleType(VehicleType.STANDARD);
        vehicle.setRegistrationExpiryDate(LocalDate.now().plusYears(1));
        vehicle.setInsuranceExpiryDate(LocalDate.now().plusYears(1));
        vehicle.setTechnicalInspectionExpiryDate(LocalDate.now().plusMonths(6));
        vehicle.setStatus(status);

        return vehicleRepository.save(vehicle);
    }

    public DriverVehicleAssignment assign(Driver driver, Vehicle vehicle) {

        DriverVehicleAssignment assignment = new DriverVehicleAssignment();
        assignment.setDriver(driver);
        assignment.setVehicle(vehicle);
        assignment.setAssignedFrom(Instant.now().minus(Duration.ofDays(1)));
        assignment.setActive(true);

        return assignmentRepository.save(assignment);
    }

    public DriverLocation location(Driver driver, double lat, double lng, Instant updatedAt) {

        DriverLocation location = new DriverLocation();
        location.setDriver(driver);
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setLocationUpdatedAt(updatedAt);

        return driverLocationRepository.save(location);
    }

    public DriverLocation freshLocation(Driver driver) {
        return location(driver, TIRANA_LAT, TIRANA_LNG, Instant.now());
    }

    /** Older than the two-minute freshness window used by taxi search. */
    public DriverLocation staleLocation(Driver driver) {
        return location(
                driver,
                TIRANA_LAT,
                TIRANA_LNG,
                Instant.now().minus(Duration.ofMinutes(10))
        );
    }

    /**
     * A company that taxi search will actually return: approved, active,
     * booking enabled, with an online driver in an active vehicle and a fresh
     * position.
     */
    @Transactional
    public BookableCompany bookableCompany() {

        TaxiCompany company = approvedCompany();

        Driver driver = driver(company, DriverStatus.ACTIVE, DriverAvailabilityStatus.ONLINE);
        Vehicle vehicle = vehicle(company, VehicleStatus.ACTIVE);

        assign(driver, vehicle);
        freshLocation(driver);

        return new BookableCompany(company, driver, vehicle);
    }

    public record BookableCompany(
            TaxiCompany company,
            Driver driver,
            Vehicle vehicle
    ) {
    }
}
