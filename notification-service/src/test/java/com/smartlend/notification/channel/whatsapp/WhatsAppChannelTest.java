package com.smartlend.notification.channel.whatsapp;

import com.smartlend.notification.channel.NotificationPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppChannelTest {

    @Mock private RestTemplate restTemplate;

    private WhatsAppChannel buildChannel(boolean enabled) {
        return new WhatsAppChannel(
                restTemplate, enabled,
                "test-token", "phone-number-id", "v19.0",
                "en_US", "loan_approved", "loan_rejected", "emi_due"
        );
    }

    // ── isEnabled ──────────────────────────────────────────────

    @Test
    void isEnabled_returnsFalseWhenConfiguredOff() {
        assertThat(buildChannel(false).isEnabled()).isFalse();
    }

    @Test
    void isEnabled_returnsTrueWhenConfiguredOn() {
        assertThat(buildChannel(true).isEnabled()).isTrue();
    }

    @Test
    void channelName_returnsWhatsAppCloud() {
        assertThat(buildChannel(true).channelName()).isEqualTo("WHATSAPP_CLOUD");
    }

    // ── phone normalisation ────────────────────────────────────

    @ParameterizedTest(name = "phone \"{0}\" → \"{1}\"")
    @CsvSource({
        "9876543210,  919876543210",   // 10-digit → prepend 91
        "+919876543210, 919876543210", // +91 prefix → strip +
        "919876543210,  919876543210", // already 91+10
        "09876543210,   919876543210"  // leading 0 → replace with 91
    })
    void send_normalizesPhoneToE164(String rawPhone, String expectedPhone) {
        WhatsAppChannel channel = buildChannel(true);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

        channel.send(new NotificationPayload(
                "LOAN_APPROVED", "u@e.com", "User", rawPhone.trim(),
                "Subject", "Body", null,
                Map.of("amount", "100000", "emiAmount", "5000", "tenureMonths", "24")
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = captor.getValue().getBody();
        assertThat(body).containsEntry("to", expectedPhone.trim());
    }

    // ── null phone ─────────────────────────────────────────────

    @Test
    void send_skipsWhenPhoneIsNull() {
        WhatsAppChannel channel = buildChannel(true);

        channel.send(new NotificationPayload(
                "LOAN_APPROVED", "u@e.com", "User", null,
                "Subject", "Body", null, Map.of()
        ));

        verifyNoInteractions(restTemplate);
    }

    // ── unsupported event ──────────────────────────────────────

    @Test
    void send_skipsForUnsupportedEventType() {
        WhatsAppChannel channel = buildChannel(true);

        channel.send(new NotificationPayload(
                "USER_REGISTERED", "u@e.com", "User", "9999999999",
                "Subject", "Body", null, Map.of()
        ));

        verifyNoInteractions(restTemplate);
    }

    // ── template name selection ────────────────────────────────

    @Test
    void send_usesLoanRejectedTemplate() {
        WhatsAppChannel channel = buildChannel(true);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

        channel.send(new NotificationPayload(
                "LOAN_REJECTED", "u@e.com", "User", "9999999999",
                "Subject", "Body", null, Map.of("loanId", "loan-1")
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));

        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) captor.getValue().getBody().get("template");
        assertThat(template).containsEntry("name", "loan_rejected");
    }
}