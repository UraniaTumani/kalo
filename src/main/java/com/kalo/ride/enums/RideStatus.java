package com.kalo.ride.enums;

import java.util.List;

/**
 * Lifecycle of a single attempt to serve a RideRequest with one taxi company.
 *
 * <pre>
 * REQUESTED -> DRIVER_ASSIGNED -> DRIVER_ARRIVING -> DRIVER_ARRIVED
 *           -> IN_PROGRESS -> COMPLETED
 *
 * REQUESTED -> DECLINED      (company refused)
 * REQUESTED -> NO_RESPONSE   (company did not answer in time)
 * any pre-start state -> CANCELLED (customer)
 * </pre>
 *
 * The company accepting a ride and assigning a driver is a single operation, so
 * there is no separate accepted-but-unassigned state.
 */
public enum RideStatus {

    REQUESTED,

    DRIVER_ASSIGNED,

    DRIVER_ARRIVING,

    DRIVER_ARRIVED,

    IN_PROGRESS,

    COMPLETED,

    DECLINED,

    CANCELLED,

    NO_RESPONSE;

    /**
     * Statuses in which a ride still occupies the customer and the driver.
     * Kept here so the rule cannot drift between search, selection and lookup.
     */
    public static final List<RideStatus> ACTIVE_STATUSES =
            List.of(
                    REQUESTED,
                    DRIVER_ASSIGNED,
                    DRIVER_ARRIVING,
                    DRIVER_ARRIVED,
                    IN_PROGRESS
            );
}
