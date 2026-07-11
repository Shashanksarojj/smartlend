package com.smartlend.notification.consumer;

import com.smartlend.notification.handler.LoanNotificationHandler;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "aws.sqs.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class SqsConsumerService {

    private final LoanNotificationHandler notificationHandler;

    @SqsListener(value = "${aws.sqs.queue-url}")
    public void handleLoanEvent(Map<String, Object> event) {
        String type = (String) event.getOrDefault("type", "");
        log.info("SQS received loan event type={} loanId={}", type, event.get("loanId"));

        switch (type) {
            case "USER_REGISTERED" -> notificationHandler.handleUserRegistered(event);
            case "LOAN_APPLIED"    -> notificationHandler.handleLoanApplied(event);
            case "APPROVED"        -> notificationHandler.handleLoanApproved(event);
            case "REJECTED"        -> notificationHandler.handleLoanRejected(event);
            case "EMI_DUE"         -> notificationHandler.handleEmiDue(event);
            default                -> log.warn("Unknown SQS event type: {}", type);
        }
    }
}
