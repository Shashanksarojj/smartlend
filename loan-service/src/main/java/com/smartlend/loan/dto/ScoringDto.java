package com.smartlend.loan.dto;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

public class ScoringDto {

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ScoringRequest {
        private Double monthlyIncome;
        private Double requestedAmount;
        private Integer tenureMonths;
        private String employmentType;
        private Integer existingLoans;    // default 0
    }

    @Data
    public static class ScoringResponse {
        private Integer creditScore;      // 300–900
        private String riskLabel;         // LOW, MEDIUM, HIGH
        private Double suggestedRate;     // recommended interest rate %
        private String recommendation;    // APPROVE or REJECT
        private String reasoning;
    }
}
