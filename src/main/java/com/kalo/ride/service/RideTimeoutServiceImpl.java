package com.kalo.ride.service;

import com.kalo.ride.entity.Ride;
import com.kalo.ride.entity.RideRequest;
import com.kalo.ride.enums.RideRequestStatus;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.repository.RideRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class RideTimeoutServiceImpl
        implements RideTimeoutService {

    private final RideRepository rideRepository;
    private final RideRequestRepository rideRequestRepository;

    /**
     * A company failing to answer is invisible to HTTP metrics: no request is
     * made, nothing returns an error, and the ride ends in a legitimate state.
     * This is the only place that failure is countable.
     */
    private final Counter timedOutRides;

    public RideTimeoutServiceImpl(
            RideRepository rideRepository,
            RideRequestRepository rideRequestRepository,
            MeterRegistry meterRegistry
    ) {

        this.rideRepository = rideRepository;
        this.rideRequestRepository = rideRequestRepository;

        this.timedOutRides = Counter.builder("kalo.ride.timed.out")
                .description("Rides that ended because the company never answered")
                .register(meterRegistry);
    }

    @Value("${app.ride.company-response-timeout-seconds}")
    private long companyResponseTimeoutSeconds;

    /**
     * One sweep. RideTimeoutScheduler decides how often this is called, which
     * keeps the schedule out of the logic and lets a test run exactly one.
     */
    @Override
    @Transactional
    public void processTimedOutRides() {

        Instant now =
                Instant.now();

        Instant cutoff =
                now.minusSeconds(
                        companyResponseTimeoutSeconds
                );

        List<Long> rideIds =
                rideRepository
                        .findIdsByStatusAndRequestedAtBefore(
                                RideStatus.REQUESTED,
                                cutoff
                        );

        for (Long rideId : rideIds) {

            Ride ride =
                    rideRepository
                            .findForUpdateById(
                                    rideId
                            )
                            .orElse(null);

            if (ride == null) {
                continue;
            }

            /*
             * Maybe company accepted the ride
             * between the first query and the lock.
             */
            if (ride.getStatus()
                    != RideStatus.REQUESTED) {

                continue;
            }

            /*
             * Re-check exact timeout after locking.
             */
            Instant timeoutAt =
                    ride.getRequestedAt()
                            .plusSeconds(
                                    companyResponseTimeoutSeconds
                            );

            if (timeoutAt.isAfter(now)) {
                continue;
            }

            ride.setStatus(
                    RideStatus.NO_RESPONSE
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

            rideRepository.save(
                    ride
            );

            timedOutRides.increment();

            log.info(
                    "Ride timed out with no company response: rideId={} companyId={} rideRequestStatus={}",
                    ride.getId(),
                    ride.getCompany().getId(),
                    rideRequest.getStatus()
            );
        }
    }
}