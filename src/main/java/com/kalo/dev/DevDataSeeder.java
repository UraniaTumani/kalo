package com.kalo.dev;

import com.kalo.assignment.entity.DriverVehicleAssignment;
import com.kalo.assignment.repository.DriverVehicleAssignmentRepository;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Demo data for local frontend development.
 *
 * Guarded twice: the {@code dev} profile and {@code app.dev.seed.enabled}.
 * Neither is set in {@code application.properties}, so this class is never
 * instantiated in production. It is also idempotent — an existing admin phone
 * means the dataset is already there and nothing is written.
 *
 * Credentials are listed in the README.
 */
@Slf4j
@Component
@Profile("dev")
@ConditionalOnProperty(
        prefix = "app.dev.seed",
        name = "enabled",
        havingValue = "true"
)
@RequiredArgsConstructor
public class DevDataSeeder implements ApplicationRunner {

    private static final String ADMIN_PHONE = "+355690000001";
    private static final String CUSTOMER_PHONE = "+355690000002";
    private static final String ABC_OWNER_PHONE = "+355690000003";
    private static final String CITY_OWNER_PHONE = "+355690000004";

    private static final double TIRANA_LATITUDE = 41.3275;
    private static final double TIRANA_LONGITUDE = 19.8187;

    private final UserRepository userRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final CompanyOperatingHoursRepository operatingHoursRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverVehicleAssignmentRepository assignmentRepository;
    private final DriverLocationRepository driverLocationRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        if (userRepository.existsByPhone(ADMIN_PHONE)) {

            log.info("Dev seed data already present, skipping");
            return;
        }

        log.info("Seeding KALO dev demo data");

        createUser(
                "Admin",
                "KALO",
                ADMIN_PHONE,
                "admin@kalo.dev",
                "Admin123!",
                UserRole.ADMIN,
                UserStatus.ACTIVE
        );

        createUser(
                "Ana",
                "Customer",
                CUSTOMER_PHONE,
                "customer@kalo.dev",
                "Customer123!",
                UserRole.CUSTOMER,
                UserStatus.ACTIVE
        );

        TaxiCompany abc = createCompany(
                "ABC Taxi SHPK",
                "ABC Taxi",
                "K12345678A",
                ABC_OWNER_PHONE,
                "abc@kalo.dev",
                "Rruga e Durresit 45, Tirane",
                Set.of(PaymentMethod.CASH, PaymentMethod.CARD_IN_CAR)
        );

        TaxiCompany city = createCompany(
                "City Taxi SHPK",
                "City Taxi",
                "K87654321B",
                CITY_OWNER_PHONE,
                "city@kalo.dev",
                "Bulevardi Bajram Curri 12, Tirane",
                Set.of(PaymentMethod.CASH)
        );

        /*
         * Offsets of roughly 0.5-1.5 km around the centre, so every driver
         * falls inside the 10 km search radius.
         */
        seedFleet(abc, "AA", 0.004, 0.005);
        seedFleet(city, "CT", -0.006, 0.003);

        log.info("Dev demo data seeded");
    }

    /**
     * Search only considers positions younger than two minutes, so without
     * this the demo would stop returning taxis a couple of minutes after
     * startup. Dev profile only.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void refreshSeededDriverPositions() {

        List<DriverLocation> locations =
                driverLocationRepository.findAll();

        if (locations.isEmpty()) {
            return;
        }

        Instant now = Instant.now();

        for (DriverLocation location : locations) {

            location.setLocationUpdatedAt(now);
        }

        driverLocationRepository.saveAll(locations);
    }

    private void seedFleet(
            TaxiCompany company,
            String platePrefix,
            double latitudeOffset,
            double longitudeOffset
    ) {

        for (int index = 1; index <= 2; index++) {

            Driver driver = new Driver();

            driver.setCompany(company);
            driver.setFirstName("Driver" + index);
            driver.setLastName(company.getDisplayName().replace(" ", ""));
            driver.setPhone(
                    "+3556911"
                            + platePrefix.charAt(0)
                            + company.getId()
                            + index
            );
            driver.setLicenseNumber(
                    platePrefix + "-LIC-" + company.getId() + index
            );
            driver.setLicenseExpiryDate(
                    LocalDate.now().plusYears(3)
            );
            driver.setDateOfBirth(
                    LocalDate.of(1990, 5, index)
            );
            driver.setStatus(DriverStatus.ACTIVE);
            driver.setAvailabilityStatus(
                    DriverAvailabilityStatus.ONLINE
            );

            Driver savedDriver =
                    driverRepository.save(driver);

            Vehicle vehicle = new Vehicle();

            vehicle.setCompany(company);
            vehicle.setPlateNumber(
                    platePrefix + company.getId() + index + "TR"
            );
            vehicle.setBrand(index == 1 ? "Skoda" : "Volkswagen");
            vehicle.setModel(index == 1 ? "Octavia" : "Passat");
            vehicle.setManufactureYear(2020);
            vehicle.setSeats(4);
            vehicle.setVehicleType(VehicleType.STANDARD);
            vehicle.setRegistrationExpiryDate(
                    LocalDate.now().plusYears(1)
            );
            vehicle.setInsuranceExpiryDate(
                    LocalDate.now().plusYears(1)
            );
            vehicle.setTechnicalInspectionExpiryDate(
                    LocalDate.now().plusYears(1)
            );
            vehicle.setStatus(VehicleStatus.ACTIVE);

            Vehicle savedVehicle =
                    vehicleRepository.save(vehicle);

            DriverVehicleAssignment assignment =
                    new DriverVehicleAssignment();

            assignment.setDriver(savedDriver);
            assignment.setVehicle(savedVehicle);
            assignment.setAssignedFrom(Instant.now());
            assignment.setAssignedUntil(null);
            assignment.setActive(true);

            assignmentRepository.save(assignment);

            DriverLocation location = new DriverLocation();

            location.setDriver(savedDriver);
            location.setLatitude(
                    TIRANA_LATITUDE + latitudeOffset * index
            );
            location.setLongitude(
                    TIRANA_LONGITUDE + longitudeOffset * index
            );
            location.setLocationUpdatedAt(Instant.now());

            driverLocationRepository.save(location);
        }
    }

    private TaxiCompany createCompany(
            String legalName,
            String displayName,
            String nipt,
            String ownerPhone,
            String email,
            String address,
            Set<PaymentMethod> paymentMethods
    ) {

        User owner = createUser(
                displayName.split(" ")[0],
                "Owner",
                ownerPhone,
                email,
                "Partner123!",
                UserRole.PARTNER,
                UserStatus.ACTIVE
        );

        TaxiCompany company = new TaxiCompany();

        company.setOwner(owner);
        company.setLegalName(legalName);
        company.setDisplayName(displayName);
        company.setNipt(nipt);
        company.setPhone(ownerPhone);
        company.setEmail(email);
        company.setAddress(address);
        company.setLicenseNumber("TAXI-" + nipt);
        company.setLicenseExpiryDate(
                LocalDate.now().plusYears(2)
        );
        company.setVerificationStatus(
                VerificationStatus.APPROVED
        );
        company.setStatus(CompanyStatus.ACTIVE);
        company.setBookingEnabled(true);
        company.setPaymentMethods(
                new java.util.HashSet<>(paymentMethods)
        );
        company.setServiceCenterLatitude(TIRANA_LATITUDE);
        company.setServiceCenterLongitude(TIRANA_LONGITUDE);
        company.setServiceRadiusKm(25.0);
        company.setTimezone("Europe/Tirane");

        TaxiCompany savedCompany =
                taxiCompanyRepository.save(company);

        /*
         * Open around the clock so the demo works at any hour.
         */
        for (DayOfWeek day : DayOfWeek.values()) {

            CompanyOperatingHours hours =
                    new CompanyOperatingHours();

            hours.setCompany(savedCompany);
            hours.setDayOfWeek(day);
            hours.setOpenTime(LocalTime.MIDNIGHT);
            hours.setCloseTime(LocalTime.MIDNIGHT);
            hours.setClosed(false);

            operatingHoursRepository.save(hours);
        }

        return savedCompany;
    }

    private User createUser(
            String firstName,
            String lastName,
            String phone,
            String email,
            String rawPassword,
            UserRole role,
            UserStatus status
    ) {

        User user = new User();

        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhone(phone);
        user.setEmail(email);
        user.setPasswordHash(
                passwordEncoder.encode(rawPassword)
        );
        user.setRole(role);
        user.setStatus(status);
        user.setPhoneVerified(true);

        return userRepository.save(user);
    }
}
