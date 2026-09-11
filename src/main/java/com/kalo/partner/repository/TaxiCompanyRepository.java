package com.kalo.partner.repository;

import com.kalo.partner.entity.TaxiCompany;
import com.kalo.partner.enums.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaxiCompanyRepository
        extends JpaRepository<TaxiCompany, Long> {

    boolean existsByNipt(String nipt);

    Optional<TaxiCompany> findByOwnerId(Long ownerId);

    Optional<TaxiCompany> findByOwnerPhone(String phone);

    List<TaxiCompany> findByVerificationStatus(
            VerificationStatus verificationStatus
    );
}