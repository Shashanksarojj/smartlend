package com.smartlend.loan.repository;

import com.smartlend.loan.model.LoanDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanDocumentRepository extends JpaRepository<LoanDocument, String> {
    List<LoanDocument> findByLoanId(String loanId);
    List<LoanDocument> findByLoanIdAndUserId(String loanId, String userId);
}
