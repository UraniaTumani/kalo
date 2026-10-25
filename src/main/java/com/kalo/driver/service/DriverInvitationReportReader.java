package com.kalo.driver.service;
/** Trusted, read-only provider lookup; no implementation is enabled by default. */
public interface DriverInvitationReportReader {
    enum Evidence { ACCEPTED, REJECTED, UNKNOWN }
    Evidence lookup(String providerMessageId, String destination);
}
