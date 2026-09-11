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
import com.kalo.rating.entity.RideRating;
import com.kalo.rating.repository.RideRatingRepository;
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

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Demo data for local frontend development.
 *
 * Guarded twice — the {@code dev} profile and {@code app.dev.seed.enabled} —
 * and neither is set in {@code application.properties}, so this class is never
 * instantiated in production. It is also idempotent: an existing admin phone
 * means the dataset is already there and nothing is written.
 *
 * Everything is written through repositories. No production service is called
 * and none of them know this class exists, so the real business flow is
 * unchanged and no security rule is bypassed — the seeded rows are exactly
 * what the normal flow would have produced.
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

    private static final double TIRANA_LATITUDE = 41.3275;
    private static final double TIRANA_LONGITUDE = 19.8187;

    private final UserRepository userRepository;
    private final TaxiCompanyRepository taxiCompanyRepository;
    private final CompanyOperatingHoursRepository operatingHoursRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverVehicleAssignmentRepository assignmentRepository;
    private final DriverLocationRepository driverLocationRepository;
    private final RideRequestRepository rideRequestRepository;
    private final RideOfferRepository rideOfferRepository;
    private final RideRepository rideRepository;
    private final RideRatingRepository rideRatingRepository;
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
                "Admin", "KALO", ADMIN_PHONE, "admin@kalo.dev",
                "Admin123!", UserRole.ADMIN, UserStatus.ACTIVE
        );

        User ana = createUser(
                "Ana", "Hoxha", "+355690000002", "ana@kalo.dev",
                "Customer123!", UserRole.CUSTOMER, UserStatus.ACTIVE
        );

        User blerim = createUser(
                "Blerim", "Krasniqi", "+355690000005", "blerim@kalo.dev",
                "Customer123!", UserRole.CUSTOMER, UserStatus.ACTIVE
        );

        User elira = createUser(
                "Elira", "Dervishi", "+355690000006", "elira@kalo.dev",
                "Customer123!", UserRole.CUSTOMER, UserStatus.ACTIVE
        );

        TaxiCompany abc = createCompany(
                "ABC Taxi SHPK",
                "ABC Taxi",
                "K12345678A",
                "Arben", "Marku",
                "+355690000003",
                "abc@kalo.dev",
                "Rruga e Durresit 45, Tirane",
                Set.of(PaymentMethod.CASH, PaymentMethod.CARD_IN_CAR)
        );

        TaxiCompany city = createCompany(
                "City Taxi SHPK",
                "City Taxi",
                "K87654321B",
                "Sokol", "Leka",
                "+355690000004",
                "city@kalo.dev",
                "Bulevardi Bajram Curri 12, Tirane",
                Set.of(PaymentMethod.CASH)
        );

        /*
         * Five drivers and five vehicles, paired one to one. Offsets keep each
         * driver roughly 0.5-2 km from the centre, inside the 10 km search
         * radius. Two are left OFFLINE so the availability rules are visible:
         * only the ONLINE ones appear in a passenger search.
         */
        List<Driver> abcDrivers = List.of(
                createFleetMember(abc, "Ilir", "Balla", 1, "AA101TR",
                        "Skoda", "Octavia", VehicleType.STANDARD,
                        DriverAvailabilityStatus.ONLINE, 0.004, 0.005),

                createFleetMember(abc, "Gent", "Prifti", 2, "AA102TR",
                        "Volkswagen", "Passat", VehicleType.STANDARD,
                        DriverAvailabilityStatus.ONLINE, -0.007, 0.009),

                createFleetMember(abc, "Mirela", "Hasa", 3, "AA103TR",
                        "Toyota", "Prius", VehicleType.ELECTRIC,
                        DriverAvailabilityStatus.OFFLINE, 0.011, -0.006)
        );

        List<Driver> cityDrivers = List.of(
                createFleetMember(city, "Fatos", "Shehu", 4, "CT201TR",
                        "Mercedes-Benz", "E-Class", VehicleType.PREMIUM,
                        DriverAvailabilityStatus.ONLINE, -0.006, -0.008),

                createFleetMember(city, "Lediana", "Cela", 5, "CT202TR",
                        "Ford", "Tourneo", VehicleType.VAN,
                        DriverAvailabilityStatus.OFFLINE, 0.013, 0.012)
        );

        /*
         * A short history so ride lists, fares and ratings are not empty on a
         * fresh database. All of these are terminal, so none of them blocks a
         * demo passenger from starting a new ride.
         */
        seedCompletedRide(ana, abc, abcDrivers.get(0),
                "Rruga e Durresit 45", "Sheshi Skenderbej",
                new BigDecimal("850.00"), 6, 5, 5, "Quick and friendly.");

        seedCompletedRide(ana, abc, abcDrivers.get(1),
                "Sheshi Skenderbej", "Bulevardi Zogu I",
                new BigDecimal("600.00"), 3, 5, 4, null);

        seedCompletedRide(blerim, city, cityDrivers.get(0),
                "Rruga Myslym Shyri", "Aeroporti i Tiranes",
                new BigDecimal("2400.00"), 2, 4, 4, "Clean car, good driver.");

        seedCompletedRide(elira, city, cityDrivers.get(1),
                "Blloku", "Stacioni i Trenit",
                new BigDecimal("750.00"), 1, 5, 5, null);

        /*
         * Rating aggregates are normally maintained by RideRatingServiceImpl.
         * Recomputing them here keeps the seeded data self-consistent without
         * the seeder reaching into a production service.
         */
        refreshRatingAggregates(
                List.of(abc, city),
                concat(abcDrivers, cityDrivers)
        );

        log.info(
                "Dev demo data seeded: {} users, {} companies, {} drivers, {} vehicles, {} rides",
                userRepository.count(),
                taxiCompanyRepository.count(),
                driverRepository.count(),
                vehicleRepository.count(),
                rideRepository.count()
        );
    }

    /**
     * Taxi search only considers positions younger than two minutes, so
     * without this the demo would stop returning taxis a couple of minutes
     * after startup. Dev profile only.
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

    /* ------------------------------------------------------------ fleet */

    /**
     * Creates one driver, the vehicle they drive, the active assignment
     * between them and their current position.
     */
    private Driver createFleetMember(
            TaxiCompany company,
            String firstName,
            String lastName,
            int index,
            String plateNumber,
            String brand,
            String model,
            VehicleType vehicleType,
            DriverAvailabilityStatus availability,
            double latitudeOffset,
            double longitudeOffset
    ) {

        Driver driver = new Driver();

        driver.setCompany(company);
        driver.setFirstName(firstName);
        driver.setLastName(lastName);
        driver.setPhone(String.format("+35569100000%d", index));
        driver.setLicenseNumber(String.format("AL-DRV-%04d", index));
        driver.setLicenseExpiryDate(LocalDate.now().plusYears(3));
        driver.setDateOfBirth(LocalDate.of(1988 + index, 3, 12));
        driver.setStatus(DriverStatus.ACTIVE);
        driver.setAvailabilityStatus(availability);

        Driver savedDriver = driverRepository.save(driver);

        Vehicle vehicle = new Vehicle();

        vehicle.setCompany(company);
        vehicle.setPlateNumber(plateNumber);
        vehicle.setBrand(brand);
        vehicle.setModel(model);
        vehicle.setManufactureYear(2019 + (index % 4));
        vehicle.setSeats(vehicleType == VehicleType.VAN ? 7 : 4);
        vehicle.setVehicleType(vehicleType);
        vehicle.setRegistrationExpiryDate(LocalDate.now().plusYears(1));
        vehicle.setInsuranceExpiryDate(LocalDate.now().plusYears(1));
        vehicle.setTechnicalInspectionExpiryDate(LocalDate.now().plusMonths(8));
        vehicle.setStatus(VehicleStatus.ACTIVE);

        Vehicle savedVehicle = vehicleRepository.save(vehicle);

        DriverVehicleAssignment assignment = new DriverVehicleAssignment();

        assignment.setDriver(savedDriver);
        assignment.setVehicle(savedVehicle);
        assignment.setAssignedFrom(Instant.now().minus(Duration.ofDays(30)));
        assignment.setAssignedUntil(null);
        assignment.setActive(true);

        assignmentRepository.save(assignment);

        DriverLocation location = new DriverLocation();

        location.setDriver(savedDriver);
        location.setLatitude(TIRANA_LATITUDE + latitudeOffset);
        location.setLongitude(TIRANA_LONGITUDE + longitudeOffset);
        location.setLocationUpdatedAt(Instant.now());

        driverLocationRepository.save(location);

        return savedDriver;
    }

    /* ----------------------------------------------------------- history */

    /**
     * Writes the full row set a finished ride leaves behind: the request, the
     * offer the passenger picked, the completed ride and its rating.
     */
    private void seedCompletedRide(
            User customer,
            TaxiCompany company,
            Driver driver,
            String pickupAddress,
            String destinationAddress,
            BigDecimal fare,
            int daysAgo,
            int driverRating,
            int companyRating,
            String comment
    ) {

        Instant requestedAt =
                Instant.now().minus(Duration.ofDays(daysAgo));

        Vehicle vehicle =
                assignmentRepository
                        .findByDriverIdAndActiveTrue(driver.getId())
                        .orElseThrow()
                        .getVehicle();

        RideRequest rideRequest = new RideRequest();

        rideRequest.setCustomer(customer);
        rideRequest.setPickupLatitude(TIRANA_LATITUDE + 0.003);
        rideRequest.setPickupLongitude(TIRANA_LONGITUDE + 0.002);
        rideRequest.setPickupAddress(pickupAddress);
        rideRequest.setDestinationLatitude(TIRANA_LATITUDE - 0.010);
        rideRequest.setDestinationLongitude(TIRANA_LONGITUDE + 0.014);
        rideRequest.setDestinationAddress(destinationAddress);
        rideRequest.setStatus(RideRequestStatus.SELECTED);
        rideRequest.setExpiresAt(requestedAt.plus(Duration.ofMinutes(5)));

        RideRequest savedRequest =
                rideRequestRepository.save(rideRequest);

        RideOffer offer = new RideOffer();

        offer.setRideRequest(savedRequest);
        offer.setCompany(company);
        offer.setNearestDriver(driver);
        offer.setVehicle(vehicle);
        offer.setDistanceKm(1.2);

        rideOfferRepository.save(offer);

        Ride ride = new Ride();

        ride.setRideRequest(savedRequest);
        ride.setCustomer(customer);
        ride.setCompany(company);
        ride.setDriver(driver);
        ride.setVehicle(vehicle);
        ride.setStatus(RideStatus.COMPLETED);
        ride.setRequestedAt(requestedAt);
        ride.setAcceptedAt(requestedAt.plus(Duration.ofSeconds(40)));
        ride.setDriverArrivingAt(requestedAt.plus(Duration.ofSeconds(60)));
        ride.setDriverArrivedAt(requestedAt.plus(Duration.ofMinutes(6)));
        ride.setStartedAt(requestedAt.plus(Duration.ofMinutes(7)));
        ride.setCompletedAt(requestedAt.plus(Duration.ofMinutes(23)));
        ride.setFinalAmount(fare);

        Ride savedRide = rideRepository.save(ride);

        RideRating rating = new RideRating();

        rating.setRide(savedRide);
        rating.setCustomer(customer);
        rating.setCompany(company);
        rating.setDriver(driver);
        rating.setDriverRating(driverRating);
        rating.setCompanyRating(companyRating);
        rating.setComment(comment);

        rideRatingRepository.save(rating);
    }

    private void refreshRatingAggregates(
            List<TaxiCompany> companies,
            List<Driver> drivers
    ) {

        for (Driver driver : drivers) {

            Double average =
                    rideRatingRepository
                            .calculateAverageDriverRating(driver.getId());

            driver.setRating(round(average));

            driver.setRatingCount(
                    Math.toIntExact(
                            rideRatingRepository.countByDriverId(driver.getId())
                    )
            );

            driverRepository.save(driver);
        }

        for (TaxiCompany company : companies) {

            Double average =
                    rideRatingRepository
                            .calculateAverageCompanyRating(company.getId());

            company.setRating(round(average));

            company.setRatingCount(
                    Math.toIntExact(
                            rideRatingRepository.countByCompanyId(company.getId())
                    )
            );

            taxiCompanyRepository.save(company);
        }
    }

    /* ----------------------------------------------------------- company */

    private TaxiCompany createCompany(
            String legalName,
            String displayName,
            String nipt,
            String ownerFirstName,
            String ownerLastName,
            String ownerPhone,
            String email,
            String address,
            Set<PaymentMethod> paymentMethods
    ) {

        User owner = createUser(
                ownerFirstName,
                ownerLastName,
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
        company.setLicenseExpiryDate(LocalDate.now().plusYears(2));
        company.setVerificationStatus(VerificationStatus.APPROVED);
        company.setStatus(CompanyStatus.ACTIVE);
        company.setBookingEnabled(true);
        company.setPaymentMethods(new HashSet<>(paymentMethods));
        company.setServiceCenterLatitude(TIRANA_LATITUDE);
        company.setServiceCenterLongitude(TIRANA_LONGITUDE);
        company.setServiceRadiusKm(25.0);
        company.setTimezone("Europe/Tirane");

        TaxiCompany savedCompany =
                taxiCompanyRepository.save(company);

        /*
         * Equal open and close times mean open all day, so the demo works at
         * whatever hour it is run. Change a row here to see a company drop out
         * of search on its closed days.
         */
        for (DayOfWeek day : DayOfWeek.values()) {

            CompanyOperatingHours hours = new CompanyOperatingHours();

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
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setStatus(status);
        user.setPhoneVerified(true);

        return userRepository.save(user);
    }

    private Double round(Double value) {

        if (value == null) {
            return null;
        }

        return Math.round(value * 100.0) / 100.0;
    }

    private List<Driver> concat(
            List<Driver> first,
            List<Driver> second
    ) {

        List<Driver> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
