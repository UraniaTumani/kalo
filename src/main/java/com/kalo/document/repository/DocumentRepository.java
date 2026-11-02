package com.kalo.document.repository;

import com.kalo.document.entity.Document;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository
        extends JpaRepository<Document, Long> {

    /** Current version of each document type; older IDs remain available as history. */
    @org.springframework.data.jpa.repository.Query("""
        SELECT d FROM Document d WHERE d.ownerType = :type AND d.ownerId = :owner
          AND NOT EXISTS (SELECT newer.id FROM Document newer
              WHERE newer.ownerType = d.ownerType AND newer.ownerId = d.ownerId
                AND newer.documentType = d.documentType AND newer.id > d.id)
        ORDER BY d.id
        """)
    List<Document> findCurrentByOwner(
            @org.springframework.data.repository.query.Param("type") DocumentOwnerType type,
            @org.springframework.data.repository.query.Param("owner") Long owner);

    List<Document> findAllByOwnerTypeAndOwnerId(
            DocumentOwnerType ownerType,
            Long ownerId
    );

    Optional<Document> findByIdAndOwnerTypeAndOwnerId(
            Long id,
            DocumentOwnerType ownerType,
            Long ownerId
    );


    Optional<Document> findByOwnerTypeAndOwnerIdAndDocumentType(
            DocumentOwnerType ownerType,
            Long ownerId,
            DocumentType documentType
    );

    List<Document> findAllByOwnerTypeAndOwnerIdAndVerificationStatus(
            DocumentOwnerType ownerType,
            Long ownerId,
            DocumentVerificationStatus verificationStatus
    );
    boolean existsByOwnerTypeAndOwnerIdAndDocumentType(
            DocumentOwnerType ownerType,
            Long ownerId,
            DocumentType documentType
    );
}