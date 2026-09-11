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