package com.kalo.support.repository;

import com.kalo.support.entity.SupportRequest;
import com.kalo.support.enums.SupportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportRequestRepository
        extends JpaRepository<SupportRequest, Long> {

    /**
     * The only way a non-admin reads support data.
     *
     * There is deliberately no findById for the owner path: a method that takes
     * a ticket id is a method someone can be tempted to call with an id off the
     * request, and the first such call is a cross-tenant leak. Scoping by user
     * id at the query means a caller can only ever be handed their own rows.
     */
    Page<SupportRequest> findByUserId(
            Long userId,
            Pageable pageable
    );

    @Query("""
            SELECT r
            FROM SupportRequest r
            WHERE (:status IS NULL OR r.status = :status)
            """)
    Page<SupportRequest> findAllByOptionalStatus(
            @Param("status") SupportStatus status,
            Pageable pageable
    );
}
