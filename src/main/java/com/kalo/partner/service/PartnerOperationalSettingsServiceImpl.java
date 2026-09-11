package com.kalo.partner.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.partner.dto.OperationalSettingsResponse;
import com.kalo.partner.dto.UpdateOperationalSettingsRequest;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;

@Service
@RequiredArgsConstructor
public class PartnerOperationalSettingsServiceImpl
        implements PartnerOperationalSettingsService {

    private final TaxiCompanyRepository taxiCompanyRepository;

    @Override
    @Transactional(readOnly = true)
    public OperationalSettingsResponse getSettings() {

        TaxiCompany company =
                getCurrentPartnerCompany();

        return mapToResponse(
                company
        );
    }

    @Override
    @Transactional
    public OperationalSettingsResponse updateSettings(
            UpdateOperationalSettingsRequest request
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        company.setBookingEnabled(
                request.bookingEnabled()
        );

        company.setPaymentMethods(
                new HashSet<>(
                        request.paymentMethods()
                )
        );

        TaxiCompany savedCompany =
                taxiCompanyRepository.save(
                        company
                );

        return mapToResponse(
                savedCompany
        );
    }

    private TaxiCompany getCurrentPartnerCompany() {

        String phone =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        TaxiCompany company =
                taxiCompanyRepository
                        .findByOwnerPhone(phone)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Taxi company not found for current partner"
                                )
                        );

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Only an approved taxi company can manage operational settings"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company must be active to manage operational settings"
            );
        }

        return company;
    }

    private OperationalSettingsResponse mapToResponse(
            TaxiCompany company
    ) {

        return new OperationalSettingsResponse(
                company.getId(),
                company.getDisplayName(),
                company.isBookingEnabled(),
                company.getPaymentMethods()
        );
    }
}