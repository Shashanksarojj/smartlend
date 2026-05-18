package com.smartlend.notification.consumer;

import com.smartlend.notification.handler.LoanNotificationHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoanEventConsumer {

    private final LoanNotificationHandler notificationHandler;

    @RabbitListener(queues = "${rabbitmq.queue.loan-events}")
    public void handleLoanEvent(Map<String, Object> event) {
        String type = (String) event.getOrDefault("type", "");
        log.info("Received loan event type={} loanId={}", type, event.get("loanId"));

        switch (type) {
            case "USER_REGISTERED" -> notificationHandler.handleUserRegistered(event);
            case "LOAN_APPLIED"    -> notificationHandler.handleLoanApplied(event);
            case "APPROVED"        -> notificationHandler.handleLoanApproved(event);
            case "REJECTED"        -> notificationHandler.handleLoanRejected(event);
            case "EMI_DUE"         -> notificationHandler.handleEmiDue(event);
            default                -> log.warn("Unknown loan event type: {}", type);
        }
    }
}
