package com.smartlend.loan.controller;

import com.smartlend.loan.dto.LoanDto;
import com.smartlend.loan.service.LoanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
@Tag(name = "Loans", description = "Apply, manage, and repay loans")
public class LoanController {

    private final LoanService loanService;

    // ── Applicant endpoints ───────────────────────────────────

    @PostMapping("/apply")
    @Operation(summary = "Apply for a loan")
    public ResponseEntity<LoanDto.LoanResponse> apply(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Monthly-Income") Double monthlyIncome,
            @RequestHeader("X-Employment-Type") String employmentType,
            @Valid @RequestBody LoanDto.ApplyRequest request) {
        return ResponseEntity.ok(loanService.applyForLoan(userId, request, monthlyIncome, employmentType));
    }

    @GetMapping("/my")
    @Operation(summary = "Get my loans")
    public ResponseEntity<List<LoanDto.LoanResponse>> myLoans(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(loanService.getMyLoans(userId));
    }

    @GetMapping("/{loanId}/emi-schedule")
    @Operation(summary = "Get EMI schedule for a loan")
    public ResponseEntity<List<LoanDto.EmiScheduleItem>> emiSchedule(@PathVariable String loanId) {
        return ResponseEntity.ok(loanService.getEmiSchedule(loanId));
    }

    // ── Admin endpoints ───────────────────────────────────────

    @GetMapping("/admin/all")
    @Operation(summary = "Admin: get all loans")
    public ResponseEntity<List<LoanDto.LoanResponse>> allLoans() {
        return ResponseEntity.ok(loanService.getAllLoans());
    }

    @PutMapping("/admin/{loanId}/decision")
    @Operation(summary = "Admin: approve or reject a loan")
    public ResponseEntity<LoanDto.LoanResponse> decide(
            @PathVariable String loanId,
            @Valid @RequestBody LoanDto.AdminDecisionRequest decision) {
        return ResponseEntity.ok(loanService.processAdminDecision(loanId, decision));
    }

    @GetMapping("/admin/summary")
    @Operation(summary = "Admin: portfolio summary")
    public ResponseEntity<LoanDto.LoanSummary> summary() {
        return ResponseEntity.ok(loanService.getAdminSummary());
    }
}
