package com.kalo.document.service;

import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.util.LicenseValidity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Required evidence for new work. Call mutations while holding the company gate. */
@Component
@RequiredArgsConstructor
public class FleetDocumentEligibility {
    private final JdbcTemplate jdbc;

    // Shared by both search queries; aliases d/v and :today belong to the caller.
    public static final String SEARCH_PREDICATE = """
        AND (SELECT count(doc.id) FROM Document doc
            WHERE doc.ownerType = com.kalo.document.enums.DocumentOwnerType.DRIVER
              AND doc.ownerId = d.id
              AND doc.documentType = com.kalo.document.enums.DocumentType.DRIVING_LICENSE
              AND doc.verificationStatus = com.kalo.document.enums.DocumentVerificationStatus.APPROVED
              AND doc.expiresAt >= :today AND (doc.issuedAt IS NULL OR doc.issuedAt <= :today)
              AND NOT EXISTS (SELECT newer.id FROM Document newer
                  WHERE newer.ownerType = doc.ownerType AND newer.ownerId = doc.ownerId
                    AND newer.documentType = doc.documentType AND newer.id > doc.id)) = 1
        AND (SELECT count(doc.id) FROM Document doc
            WHERE doc.ownerType = com.kalo.document.enums.DocumentOwnerType.VEHICLE
              AND doc.ownerId = v.id
              AND doc.documentType IN (com.kalo.document.enums.DocumentType.VEHICLE_REGISTRATION,
                  com.kalo.document.enums.DocumentType.INSURANCE,
                  com.kalo.document.enums.DocumentType.TECHNICAL_INSPECTION)
              AND doc.verificationStatus = com.kalo.document.enums.DocumentVerificationStatus.APPROVED
              AND doc.expiresAt >= :today AND (doc.issuedAt IS NULL OR doc.issuedAt <= :today)
              AND NOT EXISTS (SELECT newer.id FROM Document newer
                  WHERE newer.ownerType = doc.ownerType AND newer.ownerId = doc.ownerId
                    AND newer.documentType = doc.documentType AND newer.id > doc.id)) = 3
        """;

    public void requireEligible(Long driverId, Long vehicleId) {
        var today = LicenseValidity.today();
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM documents doc
            WHERE ((doc.owner_type = 'DRIVER' AND doc.owner_id = ? AND doc.document_type = 'DRIVING_LICENSE')
                OR (doc.owner_type = 'VEHICLE' AND doc.owner_id = ?
                    AND doc.document_type IN ('VEHICLE_REGISTRATION','INSURANCE','TECHNICAL_INSPECTION')))
              AND doc.verification_status = 'APPROVED'
              AND doc.expires_at >= ? AND (doc.issued_at IS NULL OR doc.issued_at <= ?)
              AND NOT EXISTS (SELECT 1 FROM documents newer
                  WHERE newer.owner_type = doc.owner_type AND newer.owner_id = doc.owner_id
                    AND newer.document_type = doc.document_type AND newer.id > doc.id)
            """, Long.class, driverId, vehicleId, today, today);
        if (count == null || count != 4L)
            throw new InvalidOperationException("Current approved and unexpired fleet documents are required");
    }
}
