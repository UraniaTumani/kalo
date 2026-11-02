package com.kalo.document.service;

import com.kalo.assignment.service.FleetMutationLock;
import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.common.util.LicenseValidity;
import com.kalo.document.dto.DocumentResponse;
import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentVerificationStatus;
import com.kalo.document.repository.DocumentRepository;
import com.kalo.partner.repository.TaxiCompanyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FleetDocumentRenewalService {
    private final DocumentRepository documents;
    private final TaxiCompanyRepository companies;
    private final FleetMutationLock fleetLock;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final LocalDocumentStorage storage;

    @Transactional
    public DocumentResponse renew(DocumentOwnerType type, Long owner, Long id, String number,
                                  LocalDate issued, LocalDate expires, MultipartFile file) {
        var company = currentCompany();
        fleetLock.acquire(company);
        requireOwner(type, owner, company.getId());
        var old = documents.findByIdAndOwnerTypeAndOwnerId(id, type, owner).orElseThrow(() -> missing());
        entities.refresh(old, LockModeType.PESSIMISTIC_WRITE);
        if (old.getOwnerType() != type || !owner.equals(old.getOwnerId())) throw missing();
        Long newer = jdbc.queryForObject("""
            SELECT count(*) FROM documents WHERE owner_type=? AND owner_id=? AND document_type=? AND id>?
            """, Long.class, type.name(), owner, old.getDocumentType().name(), id);
        if (newer != null && newer > 0) throw new ConflictException("A newer document version already exists");
        var today = LicenseValidity.today();
        boolean expired = old.getExpiresAt() != null && old.getExpiresAt().isBefore(today);
        if (old.getVerificationStatus() != DocumentVerificationStatus.REJECTED && !expired)
            throw new InvalidOperationException("Only rejected or expired documents can be renewed");
        if (!LicenseValidity.isValidOn(expires, today))
            throw new InvalidOperationException("A valid expiry date is required for the new document");
        if (issued != null && (issued.isAfter(today) || expires.isBefore(issued)))
            throw new InvalidOperationException("Invalid document issue date");
        if (number != null && number.length() > 100) throw new InvalidOperationException("Document number is too long");
        String reference = storage.store(file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) storage.discard(reference);
            }
        });
        var replacement = new Document();
        replacement.setOwnerType(type); replacement.setOwnerId(owner);
        replacement.setDocumentType(old.getDocumentType());
        replacement.setDocumentNumber(number == null || number.isBlank() ? null : number.trim());
        replacement.setIssuedAt(issued); replacement.setExpiresAt(expires); replacement.setFileUrl(reference);
        replacement.setVerificationStatus(DocumentVerificationStatus.PENDING);
        replacement.setRejectionReason(null);
        return response(documents.saveAndFlush(replacement));
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> history(DocumentOwnerType type, Long owner) {
        var company = currentCompany(); requireOwner(type, owner, company.getId());
        return documents.findAllByOwnerTypeAndOwnerId(type, owner).stream()
                .sorted(java.util.Comparator.comparing(Document::getId).reversed()).map(this::response).toList();
    }
    private com.kalo.partner.entity.TaxiCompany currentCompany() {
        var actor = SecurityContextHolder.getContext().getAuthentication();
        if (actor == null || !actor.isAuthenticated() || actor.getAuthorities().stream()
                .noneMatch(a -> "ROLE_PARTNER".equals(a.getAuthority()))) throw new AccessDeniedException("Partner authentication required");
        return companies.findByOwnerPhone(actor.getName()).orElseThrow(() -> missing());
    }
    private void requireOwner(DocumentOwnerType type, Long owner, Long company) {
        String table;
        if (type == DocumentOwnerType.DRIVER) table="drivers";
        else if (type == DocumentOwnerType.VEHICLE) table="vehicles";
        else throw new InvalidOperationException("Invalid fleet owner type");
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=? AND company_id=?", Long.class, owner, company);
        if (count == null || count != 1L) throw missing();
    }
    private ResourceNotFoundException missing() { return new ResourceNotFoundException("Document or owner not found"); }
    private DocumentResponse response(Document d) {
        return new DocumentResponse(d.getId(), d.getDocumentType(), d.getDocumentNumber(), d.getFileUrl(),
                d.getIssuedAt(), d.getExpiresAt(), d.getVerificationStatus(), d.getRejectionReason());
    }
}
