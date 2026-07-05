package com.smartlend.loan.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "loan_documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String loanId;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType docType;

    /**
     * S3 object key — format: {loanId}/{docType}/{uuid}-{filename}
     * Stored so we can generate presigned URLs without scanning S3.
     */
    @Column(nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private String originalFilename;

    private String contentType;
    private Long fileSize;

    @CreationTimestamp
    private LocalDateTime uploadedAt;
}
