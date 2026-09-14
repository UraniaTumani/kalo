package com.kalo.partner.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.partner.dto.*;
import com.kalo.partner.entity.CompanyOperatingHours;
import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import com.kalo.partner.repository.CompanyOperatingHoursRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PartnerAvailabilitySettingsServiceImpl
        implements PartnerAvailabilitySettingsService {

    private final TaxiCompanyRepository taxiCompanyRepository;

    private final CompanyOperatingHoursRepository
            operatingHoursRepository;

    @Override
    @Transactional
    public ServiceAreaResponse updateServiceArea(
            UpdateServiceAreaRequest request
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        validateTimezone(
                request.timezone()
        );

        company.setServiceCenterLatitude(
                request.latitude()
        );

        company.setServiceCenterLongitude(
                request.longitude()
        );

        company.setServiceRadiusKm(
                request.radiusKm()
        );

        company.setTimezone(
                request.timezone().trim()
        );

        TaxiCompany savedCompany =
                taxiCompanyRepository.save(
                        company
                );

        return mapServiceArea(
                savedCompany
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceAreaResponse getServiceArea() {

        TaxiCompany company =
                getCurrentPartnerCompany();

        return mapServiceArea(
                company
        );
    }

    @Override
    @Transactional
    public List<OperatingHoursResponse>
    updateOperatingHours(
            UpdateOperatingHoursRequest request
    ) {

        TaxiCompany company =
                getCurrentPartnerCompany();

        validateHours(
                request.hours()
        );

        operatingHoursRepository
                .deleteAllByCompanyId(
                        company.getId()
                );

        List<CompanyOperatingHours> hours =
                request.hours()
                        .stream()
                        .map(item -> {

                            CompanyOperatingHours entity =
                                    new CompanyOperatingHours();

                            entity.setCompany(
                                    company
                            );

                            entity.setDayOfWeek(
                                    item.dayOfWeek()
                            );

                            entity.setClosed(
                                    item.closed()
                            );

                            if (item.closed()) {

                                entity.setOpenTime(null);
                                entity.setCloseTime(null);

                            } else {

                                entity.setOpenTime(
                                        item.openTime()
                                );

                                entity.setCloseTime(
                                        item.closeTime()
                                );
                            }

                            return entity;
                        })
                        .toList();

        List<CompanyOperatingHours> saved =
                operatingHoursRepository
                        .saveAll(hours);

        return saved
                .stream()
                .map(this::mapHours)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperatingHoursResponse>
    getOperatingHours() {

        TaxiCompany company =
                getCurrentPartnerCompany();

        return operatingHoursRepository
                .findAllByCompanyIdOrderByDayOfWeek(
                        company.getId()
                )
                .stream()
                .map(this::mapHours)
                .toList();
    }

    private void validateHours(
            List<OperatingHoursRequest> hours
    ) {

        Set<DayOfWeek> days =
                new HashSet<>();

        for (OperatingHoursRequest item : hours) {

            if (!days.add(
                    item.dayOfWeek()
            )) {

                throw new InvalidOperationException(
                        "Duplicate operating hours for "
                                + item.dayOfWeek()
                );
            }

            if (item.closed()) {
                continue;
            }

            if (item.openTime() == null
                    || item.closeTime() == null) {

                throw new InvalidOperationException(
                        "Open and close time are required for "
                                + item.dayOfWeek()
                );
            }

            /*
             * No ordering rule. All three arrangements of the two times mean
             * something, and CompanyAvailabilityChecker has always read them
             * that way:
             *
             *   08:00 -> 22:00   a normal day
             *   20:00 -> 04:00   overnight, closing the next morning
             *   00:00 -> 00:00   open around the clock
             *
             * Requiring close to be strictly after open rejected the last two,
             * which is most of a taxi company's working week. It also made the
             * write path disagree with the read path: the seeder and the test
             * fixtures both write open-all-day rows straight to the repository,
             * so the system ran happily on data its own API refused to accept.
             */
        }
    }

    private void validateTimezone(
            String timezone
    ) {

        try {

            java.time.ZoneId.of(
                    timezone
            );

        } catch (Exception exception) {

            throw new InvalidOperationException(
                    "Invalid timezone"
            );
        }
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
                                        "Taxi company not found"
                                )
                        );

        if (company.getVerificationStatus()
                != VerificationStatus.APPROVED) {

            throw new InvalidOperationException(
                    "Taxi company must be approved"
            );
        }

        if (company.getStatus()
                != CompanyStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "Taxi company must be active"
            );
        }

        return company;
    }

    private ServiceAreaResponse mapServiceArea(
            TaxiCompany company
    ) {

        return new ServiceAreaResponse(
                company.getId(),
                company.getServiceCenterLatitude(),
                company.getServiceCenterLongitude(),
                company.getServiceRadiusKm(),
                company.getTimezone()
        );
    }

    private OperatingHoursResponse mapHours(
            CompanyOperatingHours hours
    ) {

        return new OperatingHoursResponse(
                hours.getDayOfWeek(),
                hours.getOpenTime(),
                hours.getCloseTime(),
                hours.isClosed()
        );
    }
}