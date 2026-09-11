package com.kalo.partner.repository;

import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaxiCompanyRepository
        extends JpaRepository<TaxiCompany, Long> {

    boolean existsByNipt(String nipt);

    /**
     * Initialises the lazy payment-method collection for a batch of companies
     * in a single query, so taxi search does not hit one query per company.
     */
    @Query("""
            SELECT DISTINCT c
            FROM TaxiCompany c
            LEFT JOIN FETCH c.paymentMethods
            WHERE c.id IN :companyIds
            """)
    List<TaxiCompany> findAllWithPaymentMethods(
            @Param("companyIds") Collection<Long> companyIds
    );

    Optional<TaxiCompany> findByOwnerId(Long ownerId);

    Optional<TaxiCompany> findByOwnerPhone(String phone);

    List<TaxiCompany> findByVerificationStatus(
            VerificationStatus verificationStatus
    );

    /**
     * Admin company listing. Both filters are optional.
     */
    @Query("""
            SELECT c
            FROM TaxiCompany c
            WHERE (:verificationStatus IS NULL
                   OR c.verificationStatus = :verificationStatus)
              AND (:companyStatus IS NULL
                   OR c.status = :companyStatus)
            """)
    Page<TaxiCompany> findAllByOptionalStatuses(
            @Param("verificationStatus") VerificationStatus verificationStatus,
            @Param("companyStatus") CompanyStatus companyStatus,
            Pageable pageable
    );
}