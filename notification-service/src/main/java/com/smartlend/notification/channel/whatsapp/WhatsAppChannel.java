package com.smartlend.notification.channel.whatsapp;

import com.smartlend.notification.channel.NotificationChannel;
import com.smartlend.notification.channel.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * WhatsApp Cloud API notification channel.
 *
 * Sends pre-approved template messages via Meta's Graph API.
 * Free tier: 1,000 conversations/month — sufficient for a portfolio demo.
 *
 * Setup:
 *  1. Create a Meta App at developers.facebook.com
 *  2. Add the WhatsApp product and get a test phone number
 *  3. Create message templates in Meta Business Manager (names must match config)
 *  4. Set WHATSAPP_ACCESS_TOKEN and WHATSAPP_PHONE_NUMBER_ID env vars
 *
 * Template parameter order per event:
 *  loan_approved  → {{1}} name  {{2}} amount  {{3}} emi  {{4}} tenure
 *  loan_rejected  → {{1}} name  {{2}} loanId
 *  emi_due        → {{1}} name  {{2}} amount  {{3}} dueDate
 */
@Component
@Slf4j
public class WhatsAppChannel implements NotificationChannel {

    private static final String GRAPH_API_BASE = "https://graph.facebook.com";

    private final RestTemplate restTemplate = new RestTemplate();
    private final boolean enabled;
    private final String accessToken;
    private final String phoneNumberId;
    private final String apiVersion;
    private final String langCode;
    private final String tmplApproved;
    private final String tmplRejected;
    private final String tmplEmiDue;

    public WhatsAppChannel(
            @Value("${notification.channels.whatsapp.enabled:false}") boolean enabled,
            @Value("${whatsapp.access-token:}") String accessToken,
            @Value("${whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${whatsapp.api-version:v19.0}") String apiVersion,
            @Value("${whatsapp.language-code:en}") String langCode,
            @Value("${whatsapp.templates.loan-approved:loan_approved}") String tmplApproved,
            @Value("${whatsapp.templates.loan-rejected:loan_rejected}") String tmplRejected,
            @Value("${whatsapp.templates.emi-due:emi_due}") String tmplEmiDue) {
        this.enabled       = enabled;
        this.accessToken   = accessToken;
        this.phoneNumberId = phoneNumberId;
        this.apiVersion    = apiVersion;
        this.langCode      = langCode;
        this.tmplApproved  = tmplApproved;
        this.tmplRejected  = tmplRejected;
        this.tmplEmiDue    = tmplEmiDue;
    }

    @Override
    public String channelName() {
        return "WHATSAPP_CLOUD";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationPayload payload) {
        String phone = normalizePhone(payload.recipientPhone());
        if (phone == null) {
            log.warn("WhatsApp: no phone for recipient={} event={} — skipping",
                    payload.recipientEmail(), payload.eventType());
            return;
        }

        Map<String, Object> body = buildRequestBody(phone, payload);
        if (body == null) {
            log.warn("WhatsApp: unsupported event type={} — skipping", payload.eventType());
            return;
        }

        String url = "%s/%s/%s/messages".formatted(GRAPH_API_BASE, apiVersion, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("WhatsApp message sent to={} event={}", phone, payload.eventType());
        } else {
            throw new RuntimeException("WhatsApp API returned " + response.getStatusCode()
                    + ": " + response.getBody());
        }
    }

    private Map<String, Object> buildRequestBody(String to, NotificationPayload payload) {
        Map<String, Object> meta = payload.metadata();
        String name = payload.recipientName();

        List<Map<String, Object>> params = switch (payload.eventType()) {
            case "LOAN_APPROVED" -> List.of(
                    textParam(name),
                    textParam(str(meta, "amount")),
                    textParam(str(meta, "emiAmount")),
                    textParam(str(meta, "tenureMonths"))
            );
            case "LOAN_REJECTED" -> List.of(
                    textParam(name),
                    textParam(str(meta, "loanId"))
            );
            case "EMI_DUE" -> List.of(
                    textParam(name),
                    textParam(str(meta, "amount")),
                    textParam(str(meta, "dueDate"))
            );
            default -> null;
        };

        if (params == null) return null;

        String templateName = switch (payload.eventType()) {
            case "LOAN_APPROVED" -> tmplApproved;
            case "LOAN_REJECTED" -> tmplRejected;
            case "EMI_DUE"       -> tmplEmiDue;
            default              -> null;
        };

        return Map.of(
            "messaging_product", "whatsapp",
            "to", to,
            "type", "template",
            "template", Map.of(
                "name", templateName,
                "language", Map.of("code", langCode),
                "components", List.of(
                    Map.of("type", "body", "parameters", params)
                )
            )
        );
    }

    /**
     * Normalises a phone to E.164 format without the '+' sign (WhatsApp requirement).
     * Handles: +91XXXXXXXXXX, 91XXXXXXXXXX, 0XXXXXXXXXX, XXXXXXXXXX (10-digit Indian).
     */
    private String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^\\d]", "");
        if (digits.isEmpty()) return null;
        // Strip leading country code duplicates / leading zero
        if (digits.startsWith("91") && digits.length() == 12) return digits;
        if (digits.startsWith("0")  && digits.length() == 11) return "91" + digits.substring(1);
        if (digits.length() == 10) return "91" + digits;
        return digits; // international numbers already in full form
    }

    private static Map<String, Object> textParam(String value) {
        return Map.of("type", "text", "text", value != null ? value : "");
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map != null ? map.get(key) : null;
        return v != null ? v.toString() : "";
    }
}
