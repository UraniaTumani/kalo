package com.kalo.driver.service;

/**
 * Takes drivers offline once their licence has lapsed (F36).
 *
 * Search and ride acceptance already refuse an expired licence, so this is not
 * what keeps an ineligible driver out of a ride — it is what stops the partner's
 * own fleet screen from quietly lying to them. Without it a driver sits at
 * ONLINE for months after their licence ran out, looking available and never
 * being offered anything, with nothing on the screen to say why.
 */
public interface DriverLicenseSweepService {

    /**
     * Moves every ONLINE driver whose licence has expired to OFFLINE.
     *
     * @return how many drivers were taken offline by this run, which is zero on
     *         every run after the first until another licence lapses
     */
    int takeExpiredDriversOffline();
}
