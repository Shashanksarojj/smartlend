package com.smartlend.loan.controller;

import com.smartlend.loan.audit.LoanAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
@Tag(name = "Audit", description = "Immutable loan state change history from DynamoDB")
public class LoanAuditController {

    private final LoanAuditService loanAuditService;

    /**
     * Returns the full chronological audit trail for a loan.
     * Each entry records: who changed the state, from what, to what, and when.
     * Restricted to ADMIN — applicants cannot see internal decision metadata.
     */
    @GetMapping("/admin/{loanId}/audit")
    @Operation(summary = "Admin: full audit trail for a loan (from DynamoDB)")
    public ResponseEntity<List<Map<String, String>>> getAuditHistory(@PathVariable String loanId) {
        return ResponseEntity.ok(loanAuditService.getHistory(loanId));
    }
}
