package com.smartlend.loan.service;

import com.smartlend.loan.audit.LoanAuditService;
import com.smartlend.loan.client.AiScoringClient;
import com.smartlend.loan.client.UserServiceClient;
import com.smartlend.loan.dto.LoanDto;
import com.smartlend.loan.dto.ScoringDto;
import com.smartlend.loan.model.EmiPayment;
import com.smartlend.loan.model.Loan;
import com.smartlend.loan.repository.EmiPaymentRepository;
import com.smartlend.loan.repository.LoanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanService {

    private final LoanRepository loanRepository;
    private final EmiPaymentRepository emiPaymentRepository;
    private final AiScoringClient aiScoringClient;
    private final UserServiceClient userServiceClient;
    private final RabbitTemplate rabbitTemplate;
    private final LoanAuditService auditService;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key.loan-approved}")
    private String approvedKey;

    @Value("${rabbitmq.routing-key.loan-rejected}")
    private String rejectedKey;

    @Value("${rabbitmq.routing-key.loan-applied}")
    private String appliedKey;

    @Autowired(required = false)
    private SqsClient sqsClient;

    @Value("${aws.sqs.queue-url:}")
    private String sqsQueueUrl;

    @Autowired
    private ObjectMapper objectMapper;

    public List<LoanDto.LoanResponse> getMyLoans(String userId) {
        log.debug("Fetching loans for user {}", userId);
        return loanRepository.findByUserId(userId).stream().map(this::mapToResponse).toList();
    }

    public List<LoanDto.LoanResponse> getAllLoans() {
        log.debug("Fetching all loans (admin)");
        return loanRepository.findAll().stream().map(this::mapToResponse).toList();
    }

    @Transactional
    public LoanDto.LoanResponse applyForLoan(String userId, LoanDto.ApplyRequest request,
                                              Double monthlyIncome, String employmentType) {
        log.info("Loan application started — user={} amount={} tenure={}mo",
                userId, request.getPrincipalAmount(), request.getTenureMonths());

        ScoringDto.ScoringRequest scoringReq = ScoringDto.ScoringRequest.builder()
                .monthlyIncome(monthlyIncome)
                .requestedAmount(request.getPrincipalAmount().doubleValue())
                .tenureMonths(request.getTenureMonths())
                .employmentType(employmentType)
                .existingLoans(0)
                .build();

        ScoringDto.ScoringResponse score = aiScoringClient.getScore(scoringReq);
        log.info("AI score result — user={} score={} risk={} recommendation={}",
                userId, score.getCreditScore(), score.getRiskLabel(), score.getRecommendation());

        Loan loan = Loan.builder()
                .userId(userId)
                .principalAmount(request.getPrincipalAmount())
                .tenureMonths(request.getTenureMonths())
                .purpose(request.getPurpose())
                .creditScore(score.getCreditScore())
                .riskLabel(score.getRiskLabel())
                .status(Loan.LoanStatus.PENDING)
                .outstandingAmount(request.getPrincipalAmount())
                .build();

        loan = loanRepository.save(loan);

        // Audit: record the initial application event (no fromStatus — loan is new)
        auditService.record(auditService.buildEvent(
                loan.getId(), "LOAN_APPLIED",
                null, Loan.LoanStatus.PENDING.name(),
                userId, "APPLICANT",
                Map.of("amount", loan.getPrincipalAmount(),
                       "tenureMonths", loan.getTenureMonths(),
                       "creditScore", loan.getCreditScore(),
                       "riskLabel", loan.getRiskLabel() != null ? loan.getRiskLabel() : "UNKNOWN")));

        UserServiceClient.UserProfile profile = userServiceClient.getProfile(userId);
        String userEmail = profile != null ? profile.getEmail() : "";
        String userName  = profile != null ? profile.getFullName() : "Valued Customer";
        String userPhone = profile != null && profile.getPhone() != null ? profile.getPhone() : "";

        publishEvent(appliedKey,
            Map.of("loanId", loan.getId(), "userId", userId, "userEmail", userEmail,
                   "userName", userName, "userPhone", userPhone,
                   "amount", loan.getPrincipalAmount(), "tenureMonths", loan.getTenureMonths(),
                   "creditScore", loan.getCreditScore(), "riskLabel", loan.getRiskLabel() != null ? loan.getRiskLabel() : "",
                   "type", "LOAN_APPLIED"));
        log.debug("Published loan.applied event — loanId={}", loan.getId());

        return mapToResponse(loan);
    }

    @Transactional
    public LoanDto.LoanResponse processAdminDecision(String loanId, LoanDto.AdminDecisionRequest decision) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new RuntimeException("Loan not found: " + loanId));

        log.info("Admin decision — loanId={} decision={} note={}",
                loanId, decision.getDecision(), decision.getAdminNote());

        loan.setStatus(decision.getDecision());
        loan.setAdminNote(decision.getAdminNote());

        UserServiceClient.UserProfile profile = userServiceClient.getProfile(loan.getUserId());
        String userEmail = profile != null ? profile.getEmail() : "";
        String userName  = profile != null ? profile.getFullName() : "Valued Customer";
        String userPhone = profile != null && profile.getPhone() != null ? profile.getPhone() : "";

        if (decision.getDecision() == Loan.LoanStatus.APPROVED) {
            double rate = decision.getInterestRate() != null ? decision.getInterestRate() : 12.0;
            loan.setInterestRate(rate);
            loan.setDisbursedDate(LocalDate.now());
            loan.setNextEmiDate(LocalDate.now().plusMonths(1));

            BigDecimal emi = calculateEmi(loan.getPrincipalAmount(), rate, loan.getTenureMonths());
            loan.setEmiAmount(emi);
            loan.setTotalPayable(emi.multiply(BigDecimal.valueOf(loan.getTenureMonths())));

            generateEmiSchedule(loan);
            log.info("EMI schedule generated — loanId={} emi={} totalPayable={}",
                    loanId, loan.getEmiAmount(), loan.getTotalPayable());

            // Audit: PENDING → APPROVED by admin
            auditService.record(auditService.buildEvent(
                    loanId, "LOAN_APPROVED",
                    Loan.LoanStatus.PENDING.name(), Loan.LoanStatus.APPROVED.name(),
                    "admin", "ADMIN",
                    Map.of("interestRate", rate, "emiAmount", loan.getEmiAmount(),
                           "totalPayable", loan.getTotalPayable(),
                           "adminNote", decision.getAdminNote() != null ? decision.getAdminNote() : "")));

            publishEvent(approvedKey,
                Map.of("loanId", loanId, "userId", loan.getUserId(), "userEmail", userEmail,
                       "userName", userName, "userPhone", userPhone, "amount", loan.getPrincipalAmount(),
                       "tenureMonths", loan.getTenureMonths(), "interestRate", rate,
                       "emiAmount", emi, "type", "APPROVED"));
            log.debug("Published loan.approved event — loanId={}", loanId);
        } else {
            // Audit: PENDING → REJECTED by admin
            auditService.record(auditService.buildEvent(
                    loanId, "LOAN_REJECTED",
                    Loan.LoanStatus.PENDING.name(), Loan.LoanStatus.REJECTED.name(),
                    "admin", "ADMIN",
                    Map.of("adminNote", decision.getAdminNote() != null ? decision.getAdminNote() : "")));

            publishEvent(rejectedKey,
                Map.of("loanId", loanId, "userId", loan.getUserId(), "userEmail", userEmail,
                       "userName", userName, "userPhone", userPhone, "type", "REJECTED"));
            log.debug("Published loan.rejected event — loanId={}", loanId);
        }

        return mapToResponse(loanRepository.save(loan));
    }

    public List<LoanDto.EmiScheduleItem> getEmiSchedule(String loanId) {
        List<EmiPayment> payments = emiPaymentRepository.findByLoanIdOrderByInstallmentNumber(loanId);
        return payments.stream().map(p -> {
            LoanDto.EmiScheduleItem item = new LoanDto.EmiScheduleItem();
            item.setInstallmentNumber(p.getInstallmentNumber());
            item.setEmiAmount(p.getAmount());
            item.setPrincipalComponent(p.getPrincipalComponent());
            item.setInterestComponent(p.getInterestComponent());
            item.setRemainingBalance(p.getRemainingBalance());
            item.setDueDate(p.getDueDate());
            item.setStatus(p.getStatus());
            return item;
        }).toList();
    }

    public LoanDto.LoanSummary getAdminSummary() {
        LoanDto.LoanSummary summary = new LoanDto.LoanSummary();
        summary.setTotalLoans(loanRepository.count());
        summary.setPendingLoans(loanRepository.countByStatus(Loan.LoanStatus.PENDING));
        summary.setActiveLoans(loanRepository.countByStatus(Loan.LoanStatus.ACTIVE));
        summary.setTotalDisbursed(loanRepository.totalDisbursed());
        summary.setTotalOutstanding(loanRepository.totalOutstanding());
        return summary;
    }

    /**
     * Standard EMI formula: EMI = P * r * (1+r)^n / ((1+r)^n - 1)
     */
    private BigDecimal calculateEmi(BigDecimal principal, double annualRate, int months) {
        double monthlyRate = annualRate / 12 / 100;
        double factor = Math.pow(1 + monthlyRate, months);
        double emi = principal.doubleValue() * monthlyRate * factor / (factor - 1);
        return BigDecimal.valueOf(emi).setScale(2, RoundingMode.HALF_UP);
    }

    private void generateEmiSchedule(Loan loan) {
        List<EmiPayment> schedule = new ArrayList<>();
        BigDecimal balance = loan.getPrincipalAmount();
        double monthlyRate = loan.getInterestRate() / 12 / 100;

        for (int i = 1; i <= loan.getTenureMonths(); i++) {
            BigDecimal interest = balance.multiply(BigDecimal.valueOf(monthlyRate)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principal = loan.getEmiAmount().subtract(interest);
            balance = balance.subtract(principal).max(BigDecimal.ZERO);

            EmiPayment payment = EmiPayment.builder()
                    .loanId(loan.getId())
                    .installmentNumber(i)
                    .amount(loan.getEmiAmount())
                    .principalComponent(principal)
                    .interestComponent(interest)
                    .remainingBalance(balance)
                    .dueDate(loan.getDisbursedDate().plusMonths(i))
                    .status(EmiPayment.PaymentStatus.PENDING)
                    .build();

            schedule.add(payment);
        }
        emiPaymentRepository.saveAll(schedule);
    }

    private void publishEvent(String routingKey, Map<String, Object> payload) {
        if (sqsClient != null) {
            try {
                String body = objectMapper.writeValueAsString(payload);
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(sqsQueueUrl)
                        .messageBody(body)
                        .build());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialise SQS event payload: {}", e.getMessage());
            }
        } else {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        }
    }

    private LoanDto.LoanResponse mapToResponse(Loan loan) {
        LoanDto.LoanResponse r = new LoanDto.LoanResponse();
        r.setId(loan.getId());
        r.setUserId(loan.getUserId());
        r.setPrincipalAmount(loan.getPrincipalAmount());
        r.setTenureMonths(loan.getTenureMonths());
        r.setInterestRate(loan.getInterestRate());
        r.setEmiAmount(loan.getEmiAmount());
        r.setTotalPayable(loan.getTotalPayable());
        r.setCreditScore(loan.getCreditScore());
        r.setRiskLabel(loan.getRiskLabel());
        r.setStatus(loan.getStatus());
        r.setPurpose(loan.getPurpose());
        r.setAdminNote(loan.getAdminNote());
        r.setDisbursedDate(loan.getDisbursedDate());
        r.setNextEmiDate(loan.getNextEmiDate());
        r.setOutstandingAmount(loan.getOutstandingAmount());
        r.setAppliedAt(loan.getAppliedAt());
        return r;
    }
}
