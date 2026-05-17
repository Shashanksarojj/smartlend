package com.smartlend.loan.repository;

import com.smartlend.loan.model.EmiPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmiPaymentRepository extends JpaRepository<EmiPayment, String> {
    List<EmiPayment> findByLoanIdOrderByInstallmentNumber(String loanId);
    List<EmiPayment> findByLoanIdAndStatus(String loanId, EmiPayment.PaymentStatus status);
}
