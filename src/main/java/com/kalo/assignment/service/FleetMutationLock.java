package com.kalo.assignment.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.partner.entity.TaxiCompany;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes fleet mutations within a company; callers must already own the company. */
@Component
@RequiredArgsConstructor
public class FleetMutationLock {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(TaxiCompany company) {
        var rows = jdbc.queryForList("SELECT id FROM taxi_companies WHERE id = ? FOR UPDATE", Long.class, company.getId());
        if (rows.size() != 1) throw new ResourceNotFoundException("Taxi company not found");
        // Company lookup may have happened before waiting. Refresh before authorization.
        entities.refresh(company);
    }
}
