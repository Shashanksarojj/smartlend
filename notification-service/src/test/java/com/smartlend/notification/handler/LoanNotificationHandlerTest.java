package com.smartlend.notification.handler;

import com.smartlend.notification.channel.NotificationDispatcher;
import com.smartlend.notification.channel.NotificationPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoanNotificationHandlerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private LoanNotificationHandler handler;

    // ── handleUserRegistered ───────────────────────────────────

    @Test
    void handleUserRegistered_dispatchesCorrectEventType() {
        handler.handleUserRegistered(Map.of(
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999"
        ));

        NotificationPayload payload = capturePayload();
        assertThat(payload.eventType()).isEqualTo("USER_REGISTERED");
        assertThat(payload.recipientEmail()).isEqualTo("user@example.com");
        assertThat(payload.recipientName()).isEqualTo("Test User");
    }

    // ── handleLoanApplied ──────────────────────────────────────

    @Test
    void handleLoanApplied_includesLoanDetailsInPayload() {
        handler.handleLoanApplied(Map.of(
                "loanId", "loan-123",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999",
                "amount", "200000",
                "tenureMonths", "24",
                "creditScore", "720",
                "riskLabel", "MEDIUM"
        ));

        NotificationPayload payload = capturePayload();
        assertThat(payload.eventType()).isEqualTo("LOAN_APPLIED");
        assertThat(payload.metadata()).containsKey("loanId");
        assertThat(payload.subject()).contains("Received");
    }

    // ── handleLoanApproved ─────────────────────────────────────

    @Test
    void handleLoanApproved_setsCorrectEventTypeAndMetadata() {
        handler.handleLoanApproved(Map.of(
                "loanId", "loan-123",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999",
                "amount", "300000",
                "emiAmount", "13912",
                "tenureMonths", "24",
                "interestRate", "10.5"
        ));

        NotificationPayload payload = capturePayload();
        assertThat(payload.eventType()).isEqualTo("LOAN_APPROVED");
        assertThat(payload.metadata()).containsKeys("loanId", "amount", "emiAmount", "tenureMonths");
        assertThat(payload.subject()).containsIgnoringCase("approved");
    }

    // ── handleLoanRejected ─────────────────────────────────────

    @Test
    void handleLoanRejected_usesValuedCustomerFallbackWhenNameMissing() {
        handler.handleLoanRejected(Map.of(
                "loanId", "loan-123",
                "userEmail", "user@example.com",
                "userPhone", "9999999999"
                // userName deliberately absent
        ));

        NotificationPayload payload = capturePayload();
        assertThat(payload.eventType()).isEqualTo("LOAN_REJECTED");
        assertThat(payload.recipientName()).isEqualTo("Valued Customer");
    }

    // ── handleEmiDue ───────────────────────────────────────────

    @Test
    void handleEmiDue_dispatchesEmiDueEventType() {
        handler.handleEmiDue(Map.of(
                "loanId", "loan-123",
                "userEmail", "user@example.com",
                "userName", "Test User",
                "userPhone", "9999999999",
                "amount", "13912",
                "dueDate", "2026-06-20"
        ));

        NotificationPayload payload = capturePayload();
        assertThat(payload.eventType()).isEqualTo("EMI_DUE");
        assertThat(payload.metadata()).containsKeys("loanId", "amount", "dueDate");
    }

    // ── helper ─────────────────────────────────────────────────

    private NotificationPayload capturePayload() {
        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(dispatcher).dispatch(captor.capture());
        return captor.getValue();
    }
}