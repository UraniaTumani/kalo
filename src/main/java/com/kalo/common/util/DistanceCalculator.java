package com.kalo.common.util;

public final class DistanceCalculator {

    private static final double EARTH_RADIUS_KM =
            6371.0;

    private DistanceCalculator() {
    }

    public static double calculateDistanceKm(
            double latitude1,
            double longitude1,
            double latitude2,
            double longitude2
    ) {

        double latDistance =
                Math.toRadians(
                        latitude2 - latitude1
                );

        double lonDistance =
                Math.toRadians(
                        longitude2 - longitude1
                );

        double a =
                Math.sin(latDistance / 2)
                        * Math.sin(latDistance / 2)

                        + Math.cos(
                        Math.toRadians(latitude1)
                )

                        * Math.cos(
                        Math.toRadians(latitude2)
                )

                        * Math.sin(lonDistance / 2)
                        * Math.sin(lonDistance / 2);

        double c =
                2 * Math.atan2(
                        Math.sqrt(a),
                        Math.sqrt(1 - a)
                );

        return EARTH_RADIUS_KM * c;
    }
}