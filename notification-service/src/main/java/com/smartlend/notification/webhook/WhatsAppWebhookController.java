package com.smartlend.notification.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Handles Meta's WhatsApp Cloud API webhook.
 *
 * Two responsibilities:
 *  1. GET  /webhook/whatsapp — verification challenge (required by Meta during app setup)
 *  2. POST /webhook/whatsapp — delivery status updates and incoming messages
 *
 * Setup in Meta Developer Console:
 *  App → WhatsApp → Configuration → Webhook
 *  Callback URL : https://<your-domain>/webhook/whatsapp
 *  Verify token : value of WHATSAPP_WEBHOOK_VERIFY_TOKEN env var
 *  Subscribe to : messages
 */
@RestController
@RequestMapping("/webhook/whatsapp")
@Slf4j
public class WhatsAppWebhookController {

    @Value("${whatsapp.webhook-verify-token:smartlend-webhook-secret}")
    private String verifyToken;

    /** Meta calls this once during webhook setup to confirm the endpoint is live. */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("WhatsApp webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }
        log.warn("WhatsApp webhook verification failed — token mismatch");
        return ResponseEntity.status(403).build();
    }

    /**
     * Meta sends delivery status updates (sent → delivered → read) and
     * incoming user messages here.
     * Always return 200 — Meta retries on any non-2xx response.
     */
    @PostMapping
    public ResponseEntity<Void> handleEvent(@RequestBody Map<String, Object> body) {
        try {
            List<Map<String, Object>> entries = castList(body.get("entry"));
            if (entries == null) return ResponseEntity.ok().build();

            for (Map<String, Object> entry : entries) {
                List<Map<String, Object>> changes = castList(entry.get("changes"));
                if (changes == null) continue;

                for (Map<String, Object> change : changes) {
                    Map<String, Object> value = castMap(change.get("value"));
                    if (value == null) continue;

                    processStatuses(value);
                    processMessages(value);
                }
            }
        } catch (Exception e) {
            log.error("Error processing WhatsApp webhook event: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private void processStatuses(Map<String, Object> value) {
        List<Map<String, Object>> statuses = castList(value.get("statuses"));
        if (statuses == null) return;

        for (Map<String, Object> status : statuses) {
            String msgId    = str(status, "id");
            String toPhone  = str(status, "recipient_id");
            String state    = str(status, "status");   // sent | delivered | read | failed
            log.info("WhatsApp delivery status: msgId={} to={} status={}", msgId, toPhone, state);

            if ("failed".equals(state)) {
                Map<String, Object> errors = castMap(status.get("errors"));
                log.warn("WhatsApp delivery FAILED: msgId={} to={} errors={}", msgId, toPhone, errors);
            }
        }
    }

    private void processMessages(Map<String, Object> value) {
        List<Map<String, Object>> messages = castList(value.get("messages"));
        if (messages == null) return;

        for (Map<String, Object> message : messages) {
            String from = str(message, "from");
            String type = str(message, "type");

            if ("text".equals(type)) {
                Map<String, Object> text = castMap(message.get("text"));
                log.info("Incoming WhatsApp message from={} body={}", from, str(text, "body"));
            } else {
                log.info("Incoming WhatsApp {} message from={}", type, from);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map != null ? map.get(key) : null;
        return v != null ? v.toString() : "";
    }
}
