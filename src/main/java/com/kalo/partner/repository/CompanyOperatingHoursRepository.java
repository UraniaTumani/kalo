package com.kalo.partner.repository;

import com.kalo.partner.entity.CompanyOperatingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

public interface CompanyOperatingHoursRepository
        extends JpaRepository<CompanyOperatingHours, Long> {

    List<CompanyOperatingHours>
    findAllByCompanyIdOrderByDayOfWeek(
            Long companyId
    );

    Optional<CompanyOperatingHours>
    findByCompanyIdAndDayOfWeek(
            Long companyId,
            DayOfWeek dayOfWeek
    );

    void deleteAllByCompanyId(
            Long companyId
    );
}