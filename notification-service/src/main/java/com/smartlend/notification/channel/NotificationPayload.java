package com.smartlend.notification.channel;

import java.util.Map;

/**
 * Channel-agnostic notification payload.
 * Every channel receives the same payload and extracts what it needs.
 */
public record NotificationPayload(

    /** e.g. LOAN_APPROVED, LOAN_REJECTED, EMI_DUE */
    String eventType,

    /** Recipient email address */
    String recipientEmail,

    /** Recipient display name — used for personalisation */
    String recipientName,

    /** Recipient phone in any format — WhatsApp/SMS channel normalises to E.164 */
    String recipientPhone,

    /** Email subject / Push notification title / SMS prefix */
    String subject,

    /** Plain-text body — fallback for channels that don't support HTML */
    String body,

    /** HTML body — used by email channel; null means fall back to plain text */
    String htmlBody,

    /** Arbitrary extra data (loanId, amount, dueDate, etc.) */
    Map<String, Object> metadata
) {
    /** Convenience builder without htmlBody or phone. */
    public static NotificationPayload of(
            String eventType,
            String recipientEmail,
            String recipientName,
            String subject,
            String body,
            Map<String, Object> metadata) {
        return new NotificationPayload(eventType, recipientEmail, recipientName,
                null, subject, body, null, metadata);
    }
}
