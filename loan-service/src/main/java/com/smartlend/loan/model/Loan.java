package com.smartlend.loan.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "loans")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private BigDecimal principalAmount;

    @Column(nullable = false)
    private Integer tenureMonths;

    private Double interestRate;     // annual %
    private BigDecimal emiAmount;
    private BigDecimal totalPayable;
    private Integer creditScore;
    private String riskLabel;        // LOW, MEDIUM, HIGH

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status = LoanStatus.PENDING;

    private String purpose;
    private String adminNote;
    private LocalDate disbursedDate;
    private LocalDate nextEmiDate;
    private BigDecimal outstandingAmount;

    @CreationTimestamp
    private LocalDateTime appliedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum LoanStatus {
        PENDING, APPROVED, REJECTED, ACTIVE, CLOSED, DEFAULTED
    }
}
