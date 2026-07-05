package com.smartlend.loan.service;

import com.smartlend.loan.dto.DocumentDto;
import com.smartlend.loan.model.DocumentType;
import com.smartlend.loan.model.Loan;
import com.smartlend.loan.model.LoanDocument;
import com.smartlend.loan.repository.LoanDocumentRepository;
import com.smartlend.loan.repository.LoanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/jpg", "image/png");

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final LoanDocumentRepository documentRepository;
    private final LoanRepository loanRepository;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.presigned-url-expiry-minutes:15}")
    private int presignedUrlExpiryMinutes;

    /**
     * Uploads a KYC document to S3 and records metadata in PostgreSQL.
     * S3 key format: {loanId}/{docType}/{uuid}-{sanitisedFilename}
     * The UUID prefix makes keys unique even if the same file is uploaded twice.
     */
    public DocumentDto.UploadResponse uploadDocument(
            String loanId, String userId, String docTypeStr, MultipartFile file) throws IOException {

        validateFile(file);
        verifyLoanAccess(loanId, userId);

        DocumentType docType = parseDocType(docTypeStr);

        String safeFilename = sanitise(file.getOriginalFilename());
        String s3Key = String.format("%s/%s/%s-%s", loanId, docType.name(), UUID.randomUUID(), safeFilename);

        // Stream directly from the MultipartFile — no temp file needed
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();

        s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        log.info("Document uploaded — loanId={} docType={} key={} size={}B",
                loanId, docType, s3Key, file.getSize());

        LoanDocument saved = documentRepository.save(
                LoanDocument.builder()
                        .loanId(loanId)
                        .userId(userId)
                        .docType(docType)
                        .s3Key(s3Key)
                        .originalFilename(file.getOriginalFilename())
                        .contentType(file.getContentType())
                        .fileSize(file.getSize())
                        .build());

        return DocumentDto.UploadResponse.builder()
                .documentId(saved.getId())
                .loanId(loanId)
                .docType(docType)
                .originalFilename(saved.getOriginalFilename())
                .fileSize(saved.getFileSize())
                .uploadedAt(saved.getUploadedAt())
                .build();
    }

    /**
     * Lists all documents for a loan.
     * Admins see every document; applicants see only their own.
     */
    public List<DocumentDto.DocumentInfo> listDocuments(String loanId, String userId, boolean isAdmin) {
        if (!loanRepository.existsById(loanId)) {
            throw new RuntimeException("Loan not found: " + loanId);
        }
        if (!isAdmin) {
            verifyLoanAccess(loanId, userId);
        }

        List<LoanDocument> docs = isAdmin
                ? documentRepository.findByLoanId(loanId)
                : documentRepository.findByLoanIdAndUserId(loanId, userId);

        return docs.stream().map(d -> DocumentDto.DocumentInfo.builder()
                .documentId(d.getId())
                .loanId(d.getLoanId())
                .docType(d.getDocType())
                .originalFilename(d.getOriginalFilename())
                .contentType(d.getContentType())
                .fileSize(d.getFileSize())
                .uploadedAt(d.getUploadedAt())
                .build()).toList();
    }

    /**
     * Generates a presigned S3 GET URL valid for presignedUrlExpiryMinutes.
     * The caller downloads the file directly from S3 — the service never streams bytes.
     * This is the standard pattern for large files: avoids memory pressure and lets
     * S3 handle throughput independently of the API service.
     */
    public DocumentDto.PresignedUrlResponse generatePresignedUrl(
            String loanId, String documentId, String userId, boolean isAdmin) {

        LoanDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        if (!doc.getLoanId().equals(loanId)) {
            throw new RuntimeException("Document " + documentId + " does not belong to loan " + loanId);
        }
        if (!isAdmin && !doc.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You do not have access to this document");
        }

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(doc.getS3Key())
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignedUrlExpiryMinutes))
                .getObjectRequest(getRequest)
                .build();

        String presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString();
        log.info("Presigned URL issued — documentId={} expiresIn={}min", documentId, presignedUrlExpiryMinutes);

        return DocumentDto.PresignedUrlResponse.builder()
                .documentId(documentId)
                .presignedUrl(presignedUrl)
                .expiresInSeconds(presignedUrlExpiryMinutes * 60)
                .build();
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("File size must not exceed 10 MB");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException(
                    "Unsupported file type '" + file.getContentType() + "'. Allowed: PDF, JPEG, PNG");
        }
    }

    private void verifyLoanAccess(String loanId, String userId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new RuntimeException("Loan not found: " + loanId));
        if (!loan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You do not have access to loan " + loanId);
        }
    }

    private DocumentType parseDocType(String raw) {
        try {
            return DocumentType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid docType '" + raw + "'. Allowed: " + String.join(", ",
                            java.util.Arrays.stream(DocumentType.values()).map(Enum::name).toList()));
        }
    }

    private String sanitise(String filename) {
        if (filename == null || filename.isBlank()) return "file";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
