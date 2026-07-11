package com.smartlend.notification.consumer;

import com.smartlend.notification.handler.LoanNotificationHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsConsumerServiceTest {

    @Mock private LoanNotificationHandler notificationHandler;
    @InjectMocks private SqsConsumerService consumer;

    @Test
    void handleLoanEvent_loanApplied_routesToHandler() {
        consumer.handleLoanEvent(Map.of(
                "type", "LOAN_APPLIED",
                "loanId", "loan-1",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999",
                "amount", "200000",
                "tenureMonths", "24",
                "creditScore", "720",
                "riskLabel", "MEDIUM"
        ));
        verify(notificationHandler).handleLoanApplied(anyMap());
        verifyNoMoreInteractions(notificationHandler);
    }

    @Test
    void handleLoanEvent_approved_routesToHandler() {
        consumer.handleLoanEvent(Map.of(
                "type", "APPROVED",
                "loanId", "loan-1",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999",
                "amount", "300000",
                "tenureMonths", "24",
                "interestRate", "12.0",
                "emiAmount", "14124.52"
        ));
        verify(notificationHandler).handleLoanApproved(anyMap());
        verifyNoMoreInteractions(notificationHandler);
    }

    @Test
    void handleLoanEvent_rejected_routesToHandler() {
        consumer.handleLoanEvent(Map.of(
                "type", "REJECTED",
                "loanId", "loan-1",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999"
        ));
        verify(notificationHandler).handleLoanRejected(anyMap());
        verifyNoMoreInteractions(notificationHandler);
    }

    @Test
    void handleLoanEvent_userRegistered_routesToHandler() {
        consumer.handleLoanEvent(Map.of(
                "type", "USER_REGISTERED",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999"
        ));
        verify(notificationHandler).handleUserRegistered(anyMap());
        verifyNoMoreInteractions(notificationHandler);
    }

    @Test
    void handleLoanEvent_emiDue_routesToHandler() {
        consumer.handleLoanEvent(Map.of(
                "type", "EMI_DUE",
                "loanId", "loan-1",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999",
                "amount", "14124.52",
                "dueDate", "2026-08-01"
        ));
        verify(notificationHandler).handleEmiDue(anyMap());
        verifyNoMoreInteractions(notificationHandler);
    }

    @Test
    void handleLoanEvent_unknownType_doesNotCallAnyHandler() {
        consumer.handleLoanEvent(Map.of("type", "UNKNOWN_EVENT"));
        verifyNoInteractions(notificationHandler);
    }
}
