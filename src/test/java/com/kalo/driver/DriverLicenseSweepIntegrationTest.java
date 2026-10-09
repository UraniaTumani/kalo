package com.kalo.driver;

import com.jayway.jsonpath.JsonPath;
import com.kalo.common.util.LicenseValidity;
import com.kalo.driver.entity.Driver;
import com.kalo.driver.enums.DriverAvailabilityStatus;
import com.kalo.driver.repository.DriverRepository;
import com.kalo.driver.service.DriverLicenseSweepService;
import com.kalo.ride.scheduler.SweepLock;
import com.kalo.support.AbstractIntegrationTest;
import com.kalo.support.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The sweep that takes a lapsed driver offline (F36).
 *
 * It is not what keeps an ineligible driver out of a ride — search and ride
 * acceptance already refuse one. Its job is narrower and worth stating, because
 * it bounds what the tests need to prove: it stops the partner's own fleet
 * screen showing a driver as ONLINE for months after their licence ran out,
 * available-looking and never offered anything.
 *
 * Two properties matter more than the happy path. It must be safe to run
 * repeatedly, because it runs hourly forever. And it must not touch a BUSY
 * driver, because a BUSY driver has a passenger in the car.
 */
@DisplayName("The driver licence sweep")
class DriverLicenseSweepIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCH_BODY = """
            {
              "pickupLatitude": %s, "pickupLongitude": %s,
              "pickupAddress": "Rruga e Kavajes",
              "destinationLatitude": 41.3200, "destinationLongitude": 19.8300,
              "destinationAddress": "Sheshi Skenderbej"
            }
            """.formatted(TestDataFactory.TIRANA_LAT, TestDataFactory.TIRANA_LNG);

    @Autowired
    DriverLicenseSweepService sweepService;

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    SweepLock sweepLock;

    @Test
    @DisplayName("an ONLINE driver on an expired licence is taken offline")
    void expiredOnlineDriverIsTakenOffline() {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        setExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        int takenOffline = sweepService.takeExpiredDriversOffline();

        assertThat(takenOffline).isEqualTo(1);
        assertThat(availabilityOf(taxi.driver()))
                .isEqualTo(DriverAvailabilityStatus.OFFLINE);
    }

    @Test
    @DisplayName("a driver with a valid licence is left alone")
    void validDriverIsLeftAlone() {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        assertThat(sweepService.takeExpiredDriversOffline()).isZero();
        assertThat(availabilityOf(taxi.driver()))
                .isEqualTo(DriverAvailabilityStatus.ONLINE);
    }

    @Test
    @DisplayName("a driver whose licence expires today is left online")
    void sameDayDriverIsLeftOnline() {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        setExpiry(taxi.driver(), LicenseValidity.today());

        /*
         * The boundary again, from the other side: a sweep that rounded the
         * wrong way would take a driver off the road on the last morning their
         * licence was still good.
         */
        assertThat(sweepService.takeExpiredDriversOffline()).isZero();
        assertThat(availabilityOf(taxi.driver()))
                .isEqualTo(DriverAvailabilityStatus.ONLINE);
    }

    @Test
    @DisplayName("a BUSY driver on a ride is never touched, even with an expired licence")
    void busyDriverIsNeverTouched() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();

        long rideId = bookAndAccept(taxi);

        assertThat(availabilityOf(taxi.driver()))
                .as("accepting should have made the driver BUSY")
                .isEqualTo(DriverAvailabilityStatus.BUSY);

        /* Lapses mid-journey. */
        setExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        int takenOffline = sweepService.takeExpiredDriversOffline();

        /*
         * The single most important assertion in this class. Writing OFFLINE
         * over a BUSY driver would strand them mid-ride and remove their
         * company from search, and nothing on any screen would explain it.
         */
        assertThat(takenOffline)
                .as("a BUSY driver is not a candidate")
                .isZero();

        assertThat(availabilityOf(taxi.driver()))
                .as("the driver is on a ride and must stay BUSY")
                .isEqualTo(DriverAvailabilityStatus.BUSY);

        assertThat(rideId).isPositive();
    }

    @Test
    @DisplayName("running the sweep again changes nothing")
    void sweepIsIdempotent() {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        setExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        assertThat(sweepService.takeExpiredDriversOffline()).isEqualTo(1);

        /*
         * It runs every hour forever, so "safe to repeat" is not a nicety. The
         * query selects on the state it changes, which is what makes this true
         * without a marker to keep in step.
         */
        assertThat(sweepService.takeExpiredDriversOffline()).isZero();
        assertThat(sweepService.takeExpiredDriversOffline()).isZero();

        assertThat(availabilityOf(taxi.driver()))
                .isEqualTo(DriverAvailabilityStatus.OFFLINE);
    }

    @Test
    @DisplayName("a driver taken offline is not brought back by the sweep")
    void sweepDoesNotResurrect() {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        setExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        sweepService.takeExpiredDriversOffline();

        /* Licence renewed. The sweep's job is one-directional. */
        setExpiry(taxi.driver(), LicenseValidity.today().plusYears(1));

        assertThat(sweepService.takeExpiredDriversOffline()).isZero();

        assertThat(availabilityOf(taxi.driver()))
                .as("bringing a driver back online is the partner's decision, not the sweep's")
                .isEqualTo(DriverAvailabilityStatus.OFFLINE);
    }

    @Test
    @DisplayName("one expired driver does not stop the others being swept")
    void sweepHandlesSeveralDrivers() {

        TestDataFactory.BookableCompany first = fixtures.bookableCompany();
        TestDataFactory.BookableCompany second = fixtures.bookableCompany();
        TestDataFactory.BookableCompany valid = fixtures.bookableCompany();

        setExpiry(first.driver(), LicenseValidity.today().minusDays(1));
        setExpiry(second.driver(), LicenseValidity.today().minusYears(1));

        assertThat(sweepService.takeExpiredDriversOffline()).isEqualTo(2);

        assertThat(availabilityOf(first.driver())).isEqualTo(DriverAvailabilityStatus.OFFLINE);
        assertThat(availabilityOf(second.driver())).isEqualTo(DriverAvailabilityStatus.OFFLINE);
        assertThat(availabilityOf(valid.driver())).isEqualTo(DriverAvailabilityStatus.ONLINE);
    }

    @Test
    @DisplayName("only one instance sweeps at a time")
    void onlyOneInstanceSweeps() throws Exception {

        TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
        setExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {

            CountDownLatch go = new CountDownLatch(1);

            /*
             * Both "instances" fire the timer at once, as they would in a
             * scaled-out deployment. The advisory lock is what makes the second
             * skip rather than repeat the scan, and it is a DIFFERENT lock id
             * from the ride timeout sweep -- sharing one would mean whichever
             * job fired first silently stopped the other from ever running.
             */
            Future<Boolean> one = pool.submit(() -> {
                go.await();
                return sweepLock.runExclusively(
                        SweepLock.DRIVER_LICENSE_SWEEP,
                        "Driver license sweep",
                        sweepService::takeExpiredDriversOffline
                );
            });

            Future<Boolean> two = pool.submit(() -> {
                go.await();
                return sweepLock.runExclusively(
                        SweepLock.DRIVER_LICENSE_SWEEP,
                        "Driver license sweep",
                        sweepService::takeExpiredDriversOffline
                );
            });

            go.countDown();

            boolean first = one.get(30, TimeUnit.SECONDS);
            boolean second = two.get(30, TimeUnit.SECONDS);

            /*
             * Not asserted as "the first one wins": which thread takes the lock
             * is a race. What must hold is that exactly one did the work, and
             * that skipping is reported rather than hidden.
             */
            assertThat(first ^ second)
                    .as("exactly one of two concurrent sweeps should run; got %s and %s",
                            first, second)
                    .isTrue();

            assertThat(availabilityOf(taxi.driver()))
                    .as("whichever ran, the outcome is the same")
                    .isEqualTo(DriverAvailabilityStatus.OFFLINE);

        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an acceptance racing the sweep cannot leave the driver BUSY on an expired licence")
    void acceptRacingTheSweep() throws Exception {

        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {

            TestDataFactory.BookableCompany taxi = fixtures.bookableCompany();
            long rideId = book(taxi);

            /* Lapses between the offer and the acceptance. */
            setExpiry(taxi.driver(), LicenseValidity.today().minusDays(1));

            CountDownLatch go = new CountDownLatch(1);

            Future<?> sweep = pool.submit(() -> {
                go.await();
                return sweepService.takeExpiredDriversOffline();
            });

            go.countDown();

            var response = mockMvc.perform(
                            post("/api/v1/partner/rides/" + rideId + "/accept")
                                    .header("Authorization", bearer(tokenFor(taxi.company().getOwner())))
                                    .contentType(APPLICATION_JSON)
                                    .content("{\"driverId\":%d}".formatted(taxi.driver().getId()))
                    )
                    .andReturn().getResponse();

            sweep.get(30, TimeUnit.SECONDS);

            /*
             * The acceptance must lose on its own merits, not on timing: the
             * licence check inside acceptRide runs under the same lock as the
             * status transition, so there is no ordering in which an expired
             * driver gets assigned.
             */
            assertThat(response.getStatus())
                    .as("an expired licence must be refused however the race resolves")
                    .isNotEqualTo(200);

            /*
             * The REASON is asserted, not just the refusal.
             *
             * "not 200" would also be satisfied by the sweep having taken the
             * driver offline first and the accept failing on availability
             * instead — a pass that would survive the licence check being
             * deleted. Naming the licence is what ties this test to the thing
             * it is about.
             */
            assertThat(response.getContentAsString())
                    .as("the refusal must be about the licence, not a side effect of the sweep")
                    .contains("license expired on");

            assertThat(availabilityOf(taxi.driver()))
                    .as("the driver must never end up BUSY on an expired licence")
                    .isNotEqualTo(DriverAvailabilityStatus.BUSY);

        } finally {
            pool.shutdownNow();
        }
    }

    /* ----------------------------------------------------------- helpers */

    private long book(TestDataFactory.BookableCompany taxi) throws Exception {

        String token = tokenFor(fixtures.customer());

        String search = mockMvc.perform(
                        post("/api/v1/rides/search")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content(SEARCH_BODY)
                )
                .andReturn().getResponse().getContentAsString();

        long requestId = ((Number) JsonPath.read(search, "$.rideRequestId")).longValue();

        /*
         * longValue() because JsonPath hands these back as Integer while an
         * entity id is a Long, and the two never compare equal however equal
         * the numbers are.
         */
        List<Number> raw = JsonPath.read(search, "$.taxiOptions[*].companyId");
        List<Long> companies = raw.stream().map(Number::longValue).toList();

        int index = companies.indexOf(taxi.company().getId());

        assertThat(index)
                .as("the company should be bookable before its licence is broken")
                .isGreaterThanOrEqualTo(0);

        long offerId = ((Number) JsonPath.read(
                search,
                "$.taxiOptions[" + index + "].offerId"
        )).longValue();

        String selected = mockMvc.perform(
                        post("/api/v1/rides/requests/" + requestId + "/select")
                                .header("Authorization", bearer(token))
                                .contentType(APPLICATION_JSON)
                                .content("{\"offerId\":%d}".formatted(offerId))
                )
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(selected, "$.rideId")).longValue();
    }

    private long bookAndAccept(TestDataFactory.BookableCompany taxi) throws Exception {

        long rideId = book(taxi);

        mockMvc.perform(
                        post("/api/v1/partner/rides/" + rideId + "/accept")
                                .header("Authorization", bearer(tokenFor(taxi.company().getOwner())))
                                .contentType(APPLICATION_JSON)
                                .content("{\"driverId\":%d}".formatted(taxi.driver().getId()))
                )
                .andReturn();

        return rideId;
    }

    private void setExpiry(Driver driver, LocalDate expiry) {

        Driver stored = driverRepository.findById(driver.getId()).orElseThrow();
        stored.setLicenseExpiryDate(expiry);
        driverRepository.save(stored);
    }

    private DriverAvailabilityStatus availabilityOf(Driver driver) {
        return driverRepository.findById(driver.getId()).orElseThrow().getAvailabilityStatus();
    }
}
