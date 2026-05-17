package com.smartlend.loan.repository;

import com.smartlend.loan.model.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface LoanRepository extends JpaRepository<Loan, String> {

    List<Loan> findByUserId(String userId);

    List<Loan> findByStatus(Loan.LoanStatus status);

    long countByStatus(Loan.LoanStatus status);

    @Query("SELECT COALESCE(SUM(l.principalAmount), 0) FROM Loan l WHERE l.status IN ('ACTIVE', 'CLOSED')")
    BigDecimal totalDisbursed();

    @Query("SELECT COALESCE(SUM(l.outstandingAmount), 0) FROM Loan l WHERE l.status = 'ACTIVE'")
    BigDecimal totalOutstanding();
}
