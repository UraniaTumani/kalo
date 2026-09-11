package com.kalo.ride.service;

import com.kalo.ride.entity.Ride;
import com.kalo.ride.entity.RideRequest;
import com.kalo.ride.enums.RideRequestStatus;
import com.kalo.ride.enums.RideStatus;
import com.kalo.ride.repository.RideRepository;
import com.kalo.ride.repository.RideRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RideTimeoutServiceImpl
        implements RideTimeoutService {

    private final RideRepository rideRepository;
    private final RideRequestRepository rideRequestRepository;

    @Value("${app.ride.company-response-timeout-seconds}")
    private long companyResponseTimeoutSeconds;

    @Override
    @Scheduled(fixedDelay = 5000)
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
        }
    }
}