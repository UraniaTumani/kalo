package com.kalo.document.entity;

import com.kalo.common.entity.BaseEntity;
import com.kalo.document.enums.DocumentOwnerType;
import com.kalo.document.enums.DocumentType;
import com.kalo.document.enums.DocumentVerificationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(
        name = "documents",
        indexes = {
                @Index(
                        name = "idx_documents_owner",
                        columnList = "owner_type, owner_id"
                ),
                @Index(
                        name = "idx_documents_verification_status",
                        columnList = "verification_status"
                )
        }
)
public class Document extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(
            name = "owner_type",
            nullable = false,
            length = 30
    )
    private DocumentOwnerType ownerType;

    @Column(
            name = "owner_id",
            nullable = false
    )
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "document_type",
            nullable = false,
            length = 50
    )
    private DocumentType documentType;

    @Column(
            name = "document_number",
            length = 100
    )
    private String documentNumber;

    @Column(
            name = "file_url",
            nullable = false,
            length = 1000
    )
    private String fileUrl;

    @Column(
            name = "issued_at"
    )
    private LocalDate issuedAt;

    @Column(
            name = "expires_at"
    )
    private LocalDate expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "verification_status",
            nullable = false,
            length = 30
    )
    private DocumentVerificationStatus verificationStatus;

    @Column(
            name = "rejection_reason",
            length = 1000
    )
    private String rejectionReason;
}