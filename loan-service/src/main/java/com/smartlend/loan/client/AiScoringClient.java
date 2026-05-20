package com.smartlend.loan.client;

import com.smartlend.loan.dto.ScoringDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiScoringClient {

    private final RestTemplate restTemplate;

    @Value("${services.ai-scoring-url}")
    private String aiScoringUrl;

    @CircuitBreaker(name = "ai-scoring", fallbackMethod = "fallbackScore")
    public ScoringDto.ScoringResponse getScore(ScoringDto.ScoringRequest request) {
        return restTemplate.postForObject(
            aiScoringUrl + "/score",
            request,
            ScoringDto.ScoringResponse.class
        );
    }

    // Called by the circuit breaker when open or when getScore() throws
    private ScoringDto.ScoringResponse fallbackScore(ScoringDto.ScoringRequest req, Throwable t) {
        log.error("AI scoring circuit breaker fallback triggered. Cause: {}", t.getMessage());
        ScoringDto.ScoringResponse response = new ScoringDto.ScoringResponse();
        double ratio = req.getRequestedAmount() / (req.getMonthlyIncome() * 12);

        if (ratio < 2) {
            response.setCreditScore(750);
            response.setRiskLabel("LOW");
            response.setSuggestedRate(10.5);
            response.setRecommendation("APPROVE");
        } else if (ratio < 5) {
            response.setCreditScore(620);
            response.setRiskLabel("MEDIUM");
            response.setSuggestedRate(14.0);
            response.setRecommendation("APPROVE");
        } else {
            response.setCreditScore(480);
            response.setRiskLabel("HIGH");
            response.setSuggestedRate(18.0);
            response.setRecommendation("REJECT");
        }
        response.setReasoning("Fallback rule-based scoring (ai-scoring unavailable)");
        return response;
    }
}
