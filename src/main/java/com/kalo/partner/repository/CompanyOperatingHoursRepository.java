package com.kalo.partner.repository;

import com.kalo.partner.entity.CompanyOperatingHours;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompanyOperatingHoursRepository
        extends JpaRepository<CompanyOperatingHours, Long> {

    List<CompanyOperatingHours>
    findAllByCompanyIdOrderByDayOfWeek(
            Long companyId
    );

    /**
     * Batch variant for taxi search, which evaluates many companies at once.
     */
    List<CompanyOperatingHours>
    findAllByCompanyIdIn(
            Collection<Long> companyIds
    );

    Optional<CompanyOperatingHours>
    findByCompanyIdAndDayOfWeek(
            Long companyId,
            DayOfWeek dayOfWeek
    );

    /**
     * A bulk delete that runs immediately, rather than the derived version
     * this replaces.
     *
     * Rewriting a week is delete-then-insert, and Hibernate flushes inserts
     * before deletes. The old Monday row was therefore still present when the
     * new one was inserted, so every save after a company's first one failed
     * on uk_company_operating_hours_day — a partner could set their hours once
     * and never change them. flushAutomatically issues the delete first;
     * clearAutomatically keeps the persistence context from holding rows that
     * no longer exist.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CompanyOperatingHours h WHERE h.company.id = :companyId")
    void deleteAllByCompanyId(
            @Param("companyId") Long companyId
    );
}