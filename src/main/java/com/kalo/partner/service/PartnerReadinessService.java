package com.kalo.partner.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.common.util.LicenseValidity;
import com.kalo.partner.dto.PartnerReadinessResponse;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.CompanyOperatingHoursRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
import com.kalo.ride.repository.TaxiSearchRepository;
import com.kalo.ride.service.TaxiAvailabilityFinder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Explains why a partner is not eligible for new bookings right now.
 *
 * Uses the same operating-hours rule and the same eligible-taxi predicates as
 * customer search. This is not a pickup-specific availability check: distance
 * and service-area restrictions can still exclude a company for one address.
 */
@Service
@RequiredArgsConstructor
public class PartnerReadinessService {

    private final TaxiCompanyRepository companies;
    private final CompanyOperatingHoursRepository operatingHours;
    private final CompanyAvailabilityChecker availabilityChecker;
    private final TaxiSearchRepository taxiSearch;

    @Transactional(readOnly = true)
    public PartnerReadinessResponse current() {
        String phone = SecurityContextHolder.getContext().getAuthentication().getName();
        TaxiCompany company = companies.findByOwnerPhone(phone)
                .orElseThrow(() -> new ResourceNotFoundException("Taxi company not found"));

        Instant now = Instant.now();
        List<String> blockers = new ArrayList<>();

        if (company.getVerificationStatus() != VerificationStatus.APPROVED) {
            blockers.add("NOT_APPROVED");
        }
        if (company.getStatus() != CompanyStatus.ACTIVE) {
            blockers.add("NOT_ACTIVE");
        }
        if (company.getLicenseExpiryDate() == null) {
            blockers.add("COMPANY_LICENCE_MISSING");
        } else if (!LicenseValidity.isValid(company.getLicenseExpiryDate())) {
            blockers.add("COMPANY_LICENCE_EXPIRED");
        }

        // Operational checks only make sense after onboarding: otherwise every
        // draft company would also be told that it has no eligible taxi.
        if (company.getVerificationStatus() == VerificationStatus.APPROVED
                && company.getStatus() == CompanyStatus.ACTIVE) {
            if (!company.isBookingEnabled()) {
                blockers.add("BOOKINGS_DISABLED");
            }
            if (company.getPaymentMethods() == null || company.getPaymentMethods().isEmpty()) {
                blockers.add("PAYMENT_METHOD_MISSING");
            }
            if (!availabilityChecker.isInsideOperatingHours(
                    company,
                    operatingHours.findAllByCompanyIdOrderByDayOfWeek(company.getId()),
                    now)) {
                blockers.add("OUTSIDE_OPERATING_HOURS");
            }

            if (!taxiSearch.hasEligibleTaxiForCompany(
                    company.getId(),
                    now.minus(TaxiAvailabilityFinder.MAX_LOCATION_AGE),
                    LicenseValidity.today())) {
                blockers.add("NO_ELIGIBLE_TAXI");
            }
        }

        return new PartnerReadinessResponse(blockers.isEmpty(), now, List.copyOf(blockers));
    }
}
