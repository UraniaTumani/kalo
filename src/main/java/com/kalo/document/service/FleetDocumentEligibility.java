package com.kalo.document.service;

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
            throw new FleetDocumentRequiredException(issues(driverId, vehicleId, today));
    }

    private record Evidence(String owner, String type, String status, java.time.LocalDate expiry, java.time.LocalDate issued) {}

    private java.util.List<FleetDocumentRequiredException.Issue> issues(Long driverId, Long vehicleId, java.time.LocalDate today) {
        var current = jdbc.query("""
            SELECT owner_type,document_type,verification_status,expires_at,issued_at FROM documents doc
            WHERE ((owner_type='DRIVER' AND owner_id=?) OR (owner_type='VEHICLE' AND owner_id=?))
              AND NOT EXISTS (SELECT 1 FROM documents newer WHERE newer.owner_type=doc.owner_type
                AND newer.owner_id=doc.owner_id AND newer.document_type=doc.document_type AND newer.id>doc.id)
            """, (rs, row) -> new Evidence(rs.getString("owner_type"), rs.getString("document_type"),
                rs.getString("verification_status"), rs.getObject("expires_at", java.time.LocalDate.class),
                rs.getObject("issued_at", java.time.LocalDate.class)), driverId, vehicleId);
        var issues = new java.util.ArrayList<FleetDocumentRequiredException.Issue>();
        for (String type : java.util.List.of("DRIVING_LICENSE", "VEHICLE_REGISTRATION", "INSURANCE", "TECHNICAL_INSPECTION")) {
            String owner = type.equals("DRIVING_LICENSE") ? "DRIVER" : "VEHICLE";
            var evidence = current.stream().filter(d -> d.owner().equals(owner) && d.type().equals(type)).findFirst().orElse(null);
            String reason = null;
            if (evidence == null) reason = "MISSING";
            else if (!"APPROVED".equals(evidence.status())) {
                reason = java.util.Set.of("DRAFT", "PENDING", "REJECTED").contains(evidence.status()) ? evidence.status() : "UNAPPROVED";
            } else if (evidence.expiry() == null) reason = "EXPIRY_MISSING";
            else if (evidence.expiry().isBefore(today)) reason = "EXPIRED";
            else if (evidence.issued() != null && evidence.issued().isAfter(today)) reason = "FUTURE_ISSUE_DATE";
            if (reason != null) issues.add(new FleetDocumentRequiredException.Issue(owner, type, reason));
        }
        return issues;
    }
}
