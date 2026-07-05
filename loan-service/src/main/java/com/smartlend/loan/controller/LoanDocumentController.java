package com.smartlend.loan.controller;

import com.smartlend.loan.dto.DocumentDto;
import com.smartlend.loan.service.DocumentStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "KYC document upload and retrieval")
public class LoanDocumentController {

    private final DocumentStorageService documentStorageService;

    /**
     * Upload a KYC document for a specific loan.
     * The file is streamed directly to S3 — no temp files, no memory buffering.
     *
     * Form fields:
     *   file    — multipart binary (PDF / JPEG / PNG, max 10 MB)
     *   docType — one of: INCOME_PROOF, IDENTITY_PROOF, ADDRESS_PROOF,
     *             BANK_STATEMENT, EMPLOYMENT_LETTER, OTHER
     */
    @PostMapping(value = "/{loanId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a KYC document (PDF/JPEG/PNG, max 10 MB)")
    public ResponseEntity<DocumentDto.UploadResponse> uploadDocument(
            @PathVariable String loanId,
            @RequestParam("docType") String docType,
            @RequestPart("file") MultipartFile file,
            @RequestHeader("X-User-Id") String userId) throws IOException {

        return ResponseEntity.ok(documentStorageService.uploadDocument(loanId, userId, docType, file));
    }

    /**
     * List all documents for a loan.
     * Admins see all documents; applicants see only documents they uploaded.
     */
    @GetMapping("/{loanId}/documents")
    @Operation(summary = "List documents uploaded for a loan")
    public ResponseEntity<List<DocumentDto.DocumentInfo>> listDocuments(
            @PathVariable String loanId,
            @RequestHeader("X-User-Id") String userId,
            Authentication auth) {

        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(documentStorageService.listDocuments(loanId, userId, isAdmin));
    }

    /**
     * Generate a presigned S3 URL for direct document download.
     * URL expires in 15 minutes. The browser/client downloads the file
     * straight from S3 — the service is never in the download path.
     */
    @GetMapping("/{loanId}/documents/{documentId}/url")
    @Operation(summary = "Get a 15-min presigned download URL for a document")
    public ResponseEntity<DocumentDto.PresignedUrlResponse> getPresignedUrl(
            @PathVariable String loanId,
            @PathVariable String documentId,
            @RequestHeader("X-User-Id") String userId,
            Authentication auth) {

        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(
                documentStorageService.generatePresignedUrl(loanId, documentId, userId, isAdmin));
    }
}
