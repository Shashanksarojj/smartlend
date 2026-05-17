package com.smartlend.loan.dto;

import com.smartlend.loan.model.Loan;
import com.smartlend.loan.model.EmiPayment;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class LoanDto {

    @Data
    public static class ApplyRequest {
        @NotNull @DecimalMin("10000")
        private BigDecimal principalAmount;

        @NotNull @Min(3) @Max(84)
        private Integer tenureMonths;

        @NotBlank
        private String purpose;
    }

    @Data
    public static class LoanResponse {
        private String id;
        private String userId;
        private BigDecimal principalAmount;
        private Integer tenureMonths;
        private Double interestRate;
        private BigDecimal emiAmount;
        private BigDecimal totalPayable;
        private Integer creditScore;
        private String riskLabel;
        private Loan.LoanStatus status;
        private String purpose;
        private String adminNote;
        private LocalDate disbursedDate;
        private LocalDate nextEmiDate;
        private BigDecimal outstandingAmount;
        private LocalDateTime appliedAt;
    }

    @Data
    public static class AdminDecisionRequest {
        @NotNull
        private Loan.LoanStatus decision;  // APPROVED or REJECTED
        private String adminNote;
        private Double interestRate;       // admin sets the rate on approval
    }

    @Data
    public static class EmiScheduleItem {
        private Integer installmentNumber;
        private BigDecimal emiAmount;
        private BigDecimal principalComponent;
        private BigDecimal interestComponent;
        private BigDecimal remainingBalance;
        private LocalDate dueDate;
        private EmiPayment.PaymentStatus status;
    }

    @Data
    public static class LoanSummary {
        private Long totalLoans;
        private Long pendingLoans;
        private Long activeLoans;
        private BigDecimal totalDisbursed;
        private BigDecimal totalOutstanding;
    }
}
