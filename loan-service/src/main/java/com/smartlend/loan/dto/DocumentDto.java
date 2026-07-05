package com.smartlend.loan.dto;

import com.smartlend.loan.model.DocumentType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

public class DocumentDto {

    @Data
    @Builder
    public static class UploadResponse {
        private String documentId;
        private String loanId;
        private DocumentType docType;
        private String originalFilename;
        private Long fileSize;
        private LocalDateTime uploadedAt;
    }

    @Data
    @Builder
    public static class DocumentInfo {
        private String documentId;
        private String loanId;
        private DocumentType docType;
        private String originalFilename;
        private String contentType;
        private Long fileSize;
        private LocalDateTime uploadedAt;
    }

    @Data
    @Builder
    public static class PresignedUrlResponse {
        private String documentId;
        private String presignedUrl;
        private int expiresInSeconds;
    }
}
