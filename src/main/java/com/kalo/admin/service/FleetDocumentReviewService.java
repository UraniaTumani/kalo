package com.kalo.admin.service;

import com.kalo.admin.dto.FleetDocumentReviewResponse;
import com.kalo.assignment.service.FleetMutationLock;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.common.util.LicenseValidity;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.document.service.LocalDocumentStorage;
import com.kalo.partner.repository.TaxiCompanyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FleetDocumentReviewService {
    private final DocumentRepository documents;
    private final TaxiCompanyRepository companies;
    private final FleetMutationLock fleetLock;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final LocalDocumentStorage storage;

    @Transactional(readOnly = true)
    public List<FleetDocumentReviewResponse> list(Long company, DocumentOwnerType type, Long owner) {
        requireAdmin(); requireOwner(company, type, owner);
        return documents.findAllByOwnerTypeAndOwnerId(type, owner).stream()
                .sorted(java.util.Comparator.comparing(Document::getId)).map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public LocalDocumentStorage.StoredDocument file(Long company, DocumentOwnerType type, Long owner, Long id) {
        requireAdmin(); requireOwner(company, type, owner);
        return storage.read(document(type, owner, id).getFileUrl());
    }

    @Transactional
    public FleetDocumentReviewResponse decide(Long company, DocumentOwnerType type, Long owner, Long id,
                                               boolean approve, String reason) {
        requireAdmin(); requireOwner(company, type, owner);
        var tenant = companies.findById(company).orElseThrow(() -> missing());
        fleetLock.acquire(tenant);
        requireOwner(company, type, owner);
        var document = document(type, owner, id);
        entities.refresh(document, LockModeType.PESSIMISTIC_WRITE);
        if (document.getOwnerType() != type || !owner.equals(document.getOwnerId())) throw missing();
        if (document.getVerificationStatus() != DocumentVerificationStatus.DRAFT
                && document.getVerificationStatus() != DocumentVerificationStatus.PENDING)
            throw new InvalidOperationException("Only draft or pending fleet documents can be reviewed");
        if (approve) {
            var today = LicenseValidity.today();
            if (document.getIssuedAt() != null && document.getIssuedAt().isAfter(today))
                throw new InvalidOperationException("Document issue date is in the future");
            if (document.getExpiresAt() != null && !LicenseValidity.isValidOn(document.getExpiresAt(), today))
                throw new InvalidOperationException("Document has expired");
            // An approval must not be recorded for a missing/unreadable uploaded file.
            storage.read(document.getFileUrl());
            document.setVerificationStatus(DocumentVerificationStatus.APPROVED);
            document.setRejectionReason(null);
        } else {
            if (reason == null || reason.isBlank() || reason.length() > 1000)
                throw new InvalidOperationException("A rejection reason of at most 1000 characters is required");
            document.setVerificationStatus(DocumentVerificationStatus.REJECTED);
            document.setRejectionReason(reason.trim());
        }
        documents.saveAndFlush(document);
        log.info("Fleet document reviewed: companyId={} ownerType={} ownerId={} documentId={} decision={}",
                company, type, owner, id,
                document.getVerificationStatus());
        return response(document);
    }

    private void requireOwner(Long company, DocumentOwnerType type, Long owner) {
        String table;
        if (type == DocumentOwnerType.DRIVER) table = "drivers";
        else if (type == DocumentOwnerType.VEHICLE) table = "vehicles";
        else throw new InvalidOperationException("Only DRIVER or VEHICLE documents are supported");
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=? AND company_id=?", Long.class, owner, company);
        if (count == null || count != 1L) throw missing();
    }
    private Document document(DocumentOwnerType type, Long owner, Long id) {
        return documents.findByIdAndOwnerTypeAndOwnerId(id, type, owner).orElseThrow(() -> missing());
    }
    private void requireAdmin() {
        var actor = SecurityContextHolder.getContext().getAuthentication();
        if (actor == null || !actor.isAuthenticated() || actor.getAuthorities().stream()
                .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) throw new AccessDeniedException("Admin authentication required");
    }
    private ResourceNotFoundException missing() { return new ResourceNotFoundException("Fleet document or owner not found"); }
    private FleetDocumentReviewResponse response(Document d) {
        return new FleetDocumentReviewResponse(d.getId(), d.getDocumentType(), d.getDocumentNumber(),
                d.getIssuedAt(), d.getExpiresAt(), d.getVerificationStatus(), d.getRejectionReason());
    }
}
