package com.smartlend.loan.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanAuditService {

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.dynamodb.table-name:loan-audit-log}")
    private String tableName;

    /**
     * Write an immutable audit event to DynamoDB.
     *
     * Why DynamoDB for audit logs (not PostgreSQL)?
     * - Append-only writes map perfectly to DynamoDB's model — no UPDATE ever runs.
     * - PK=loanId gives O(1) lookup for a loan's full history.
     * - In production: DynamoDB Streams + Lambda can feed a compliance data lake
     *   (S3 + Athena) for regulatory reporting with zero changes to this service.
     * - Unbounded growth: audit tables grow forever; DynamoDB scales without
     *   vacuuming, reindexing, or bloat unlike PostgreSQL.
     */
    public void record(LoanAuditEvent event) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("loanId",    s(event.getLoanId()));
            item.put("sk",        s(event.getSk()));
            item.put("eventId",   s(event.getEventId()));
            item.put("eventType", s(event.getEventType()));
            item.put("toStatus",  s(event.getToStatus()));
            item.put("actorId",   s(event.getActorId()));
            item.put("actorRole", s(event.getActorRole()));
            item.put("timestamp", s(event.getTimestamp()));

            // Nullable fields — DynamoDB has no NULL type in queries; omit the attribute instead
            if (event.getFromStatus() != null) item.put("fromStatus", s(event.getFromStatus()));
            if (event.getMetadata()   != null) item.put("metadata",   s(event.getMetadata()));

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    // Condition: reject if SK already exists — audit log is immutable
                    .conditionExpression("attribute_not_exists(sk)")
                    .build());

            log.info("Audit event recorded — loanId={} type={} actor={}",
                    event.getLoanId(), event.getEventType(), event.getActorId());

        } catch (ConditionalCheckFailedException e) {
            // Duplicate write (retry scenario) — safe to ignore, event is already persisted
            log.warn("Duplicate audit event skipped — sk={}", event.getSk());
        } catch (Exception e) {
            // Audit failures must never break the main loan flow — log and continue
            log.error("Failed to write audit event — loanId={} type={}: {}",
                    event.getLoanId(), event.getEventType(), e.getMessage());
        }
    }

    /**
     * Retrieve the full history for a loan, sorted chronologically.
     * DynamoDB returns items in SK order within a partition — ISO-8601 SK gives
     * chronological sort with no extra sorting step.
     */
    public List<Map<String, String>> getHistory(String loanId) {
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("loanId = :pk")
                .expressionAttributeValues(Map.of(":pk", s(loanId)))
                .scanIndexForward(true) // ascending SK = chronological
                .build());

        return response.items().stream()
                .map(item -> item.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().s())))
                .toList();
    }

    /**
     * Build a new audit event with a timestamped SK.
     * Called by LoanService at every state transition.
     */
    public LoanAuditEvent buildEvent(String loanId, String eventType,
                                     String fromStatus, String toStatus,
                                     String actorId, String actorRole,
                                     Map<String, Object> metadata) {
        String eventId  = UUID.randomUUID().toString();
        String now      = Instant.now().toString();
        String sk       = now + "#" + eventId; // ISO prefix → natural chronological sort

        String metaJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try { metaJson = objectMapper.writeValueAsString(metadata); }
            catch (JsonProcessingException e) { metaJson = metadata.toString(); }
        }

        return LoanAuditEvent.builder()
                .loanId(loanId)
                .sk(sk)
                .eventId(eventId)
                .eventType(eventType)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actorId(actorId)
                .actorRole(actorRole)
                .metadata(metaJson)
                .timestamp(now)
                .build();
    }

    private AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
