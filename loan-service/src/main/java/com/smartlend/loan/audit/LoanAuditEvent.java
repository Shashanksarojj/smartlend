package com.smartlend.loan.audit;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable record of a single loan state transition.
 * Written to DynamoDB; never updated or deleted (append-only).
 */
@Value
@Builder
public class LoanAuditEvent {

    /**
     * DynamoDB PK — all events for a loan share the same partition.
     * Query by loanId returns the full chronological history in one request.
     */
    String loanId;

    /**
     * DynamoDB SK — ISO-8601 timestamp # UUID.
     * Lexicographic sort gives chronological order for free:
     *   2026-07-06T10:00:00Z#uuid-1   (earlier)
     *   2026-07-06T10:30:00Z#uuid-2   (later)
     */
    String sk;

    String eventId;
    String eventType;     // LOAN_APPLIED, LOAN_APPROVED, LOAN_REJECTED, DOCUMENT_UPLOADED …
    String fromStatus;    // nullable — null on LOAN_APPLIED (no prior state)
    String toStatus;
    String actorId;       // userId of who triggered the change
    String actorRole;     // APPLICANT or ADMIN
    String metadata;      // JSON string — event-specific extra data (amount, score, …)
    String timestamp;     // ISO-8601 UTC
}
