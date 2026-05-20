package com.smartlend.loan.service;

import com.smartlend.loan.client.AiScoringClient;
import com.smartlend.loan.client.UserServiceClient;
import com.smartlend.loan.dto.LoanDto;
import com.smartlend.loan.dto.ScoringDto;
import com.smartlend.loan.model.Loan;
import com.smartlend.loan.repository.EmiPaymentRepository;
import com.smartlend.loan.repository.LoanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoanServiceTest {

    @Mock private LoanRepository loanRepository;
    @Mock private EmiPaymentRepository emiPaymentRepository;
    @Mock private AiScoringClient aiScoringClient;
    @Mock private UserServiceClient userServiceClient;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks private LoanService loanService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(loanService, "exchange", "smartlend.exchange");
        ReflectionTestUtils.setField(loanService, "approvedKey", "loan.approved");
        ReflectionTestUtils.setField(loanService, "rejectedKey", "loan.rejected");
        ReflectionTestUtils.setField(loanService, "appliedKey", "loan.applied");
    }

    // ── applyForLoan ───────────────────────────────────────────

    @Test
    void applyForLoan_persistsLoanWithAiScore() {
        LoanDto.ApplyRequest req = new LoanDto.ApplyRequest();
        req.setPrincipalAmount(new BigDecimal("200000"));
        req.setTenureMonths(24);
        req.setPurpose("Home renovation");

        ScoringDto.ScoringResponse score = new ScoringDto.ScoringResponse();
        score.setCreditScore(720);
        score.setRiskLabel("MEDIUM");
        score.setRecommendation("APPROVE");

        when(aiScoringClient.getScore(any())).thenReturn(score);
        when(userServiceClient.getProfile("user-1")).thenReturn(mockProfile());

        Loan saved = loanWithId("loan-1", "user-1", new BigDecimal("200000"), 24);
        saved.setCreditScore(720);
        saved.setRiskLabel("MEDIUM");
        when(loanRepository.save(any(Loan.class))).thenReturn(saved);

        LoanDto.LoanResponse res = loanService.applyForLoan("user-1", req, 60000.0, "SALARIED");

        assertThat(res.getCreditScore()).isEqualTo(720);
        assertThat(res.getRiskLabel()).isEqualTo("MEDIUM");
        assertThat(res.getStatus()).isEqualTo(Loan.LoanStatus.PENDING);
        verify(rabbitTemplate).convertAndSend(eq("smartlend.exchange"), eq("loan.applied"), anyMap());
    }

    // ── processAdminDecision — APPROVED ────────────────────────

    @Test
    void processAdminDecision_approvedLoan_computesEmiAndPublishesEvent() {
        Loan loan = loanWithId("loan-1", "user-1", new BigDecimal("300000"), 24);
        loan.setStatus(Loan.LoanStatus.PENDING);

        when(loanRepository.findById("loan-1")).thenReturn(Optional.of(loan));
        when(userServiceClient.getProfile("user-1")).thenReturn(mockProfile());
        when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));
        when(emiPaymentRepository.saveAll(any())).thenReturn(List.of());

        LoanDto.AdminDecisionRequest decision = new LoanDto.AdminDecisionRequest();
        decision.setDecision(Loan.LoanStatus.APPROVED);
        decision.setAdminNote("Looks good");
        decision.setInterestRate(12.0);

        LoanDto.LoanResponse res = loanService.processAdminDecision("loan-1", decision);

        assertThat(res.getStatus()).isEqualTo(Loan.LoanStatus.APPROVED);
        assertThat(res.getEmiAmount()).isNotNull().isPositive();
        assertThat(res.getTotalPayable()).isNotNull().isPositive();
        assertThat(res.getDisbursedDate()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(rabbitTemplate).convertAndSend(eq("smartlend.exchange"), eq("loan.approved"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).containsEntry("type", "APPROVED");
    }

    @Test
    void processAdminDecision_approvedLoan_emiFormulaIsCorrect() {
        // P=120000, rate=12%, n=12 → EMI ≈ 10661.85
        Loan loan = loanWithId("loan-1", "user-1", new BigDecimal("120000"), 12);

        when(loanRepository.findById("loan-1")).thenReturn(Optional.of(loan));
        when(userServiceClient.getProfile("user-1")).thenReturn(mockProfile());
        when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));
        when(emiPaymentRepository.saveAll(any())).thenReturn(List.of());

        LoanDto.AdminDecisionRequest decision = new LoanDto.AdminDecisionRequest();
        decision.setDecision(Loan.LoanStatus.APPROVED);
        decision.setInterestRate(12.0);

        LoanDto.LoanResponse res = loanService.processAdminDecision("loan-1", decision);

        // EMI = 120000 * 0.01 * (1.01)^12 / ((1.01)^12 - 1) ≈ 10661.85
        assertThat(res.getEmiAmount().doubleValue()).isCloseTo(10661.85, within(1.0));
        assertThat(res.getTotalPayable().doubleValue()).isCloseTo(10661.85 * 12, within(5.0));
    }

    // ── processAdminDecision — REJECTED ───────────────────────

    @Test
    void processAdminDecision_rejectedLoan_publishesRejectedEvent() {
        Loan loan = loanWithId("loan-1", "user-1", new BigDecimal("200000"), 24);
        loan.setStatus(Loan.LoanStatus.PENDING);

        when(loanRepository.findById("loan-1")).thenReturn(Optional.of(loan));
        when(userServiceClient.getProfile("user-1")).thenReturn(mockProfile());
        when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

        LoanDto.AdminDecisionRequest decision = new LoanDto.AdminDecisionRequest();
        decision.setDecision(Loan.LoanStatus.REJECTED);
        decision.setAdminNote("High risk");

        LoanDto.LoanResponse res = loanService.processAdminDecision("loan-1", decision);

        assertThat(res.getStatus()).isEqualTo(Loan.LoanStatus.REJECTED);
        assertThat(res.getEmiAmount()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(rabbitTemplate).convertAndSend(eq("smartlend.exchange"), eq("loan.rejected"), captor.capture());
        assertThat(captor.getValue()).containsEntry("type", "REJECTED");
    }

    @Test
    void processAdminDecision_throwsForUnknownLoan() {
        when(loanRepository.findById("bad-id")).thenReturn(Optional.empty());

        LoanDto.AdminDecisionRequest decision = new LoanDto.AdminDecisionRequest();
        decision.setDecision(Loan.LoanStatus.APPROVED);

        assertThatThrownBy(() -> loanService.processAdminDecision("bad-id", decision))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Loan not found");
    }

    // ── getMyLoans ─────────────────────────────────────────────

    @Test
    void getMyLoans_returnsOnlyCallerLoans() {
        Loan l1 = loanWithId("loan-1", "user-1", new BigDecimal("100000"), 12);
        Loan l2 = loanWithId("loan-2", "user-1", new BigDecimal("200000"), 24);
        when(loanRepository.findByUserId("user-1")).thenReturn(List.of(l1, l2));

        List<LoanDto.LoanResponse> result = loanService.getMyLoans("user-1");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(r -> r.getUserId().equals("user-1"));
    }

    // ── helpers ────────────────────────────────────────────────

    private Loan loanWithId(String id, String userId, BigDecimal amount, int tenure) {
        return Loan.builder()
                .id(id).userId(userId)
                .principalAmount(amount).tenureMonths(tenure)
                .outstandingAmount(amount)
                .status(Loan.LoanStatus.PENDING)
                .build();
    }

    private UserServiceClient.UserProfile mockProfile() {
        UserServiceClient.UserProfile p = new UserServiceClient.UserProfile();
        p.setEmail("user@example.com");
        p.setFullName("Test User");
        p.setPhone("9999999999");
        return p;
    }
}