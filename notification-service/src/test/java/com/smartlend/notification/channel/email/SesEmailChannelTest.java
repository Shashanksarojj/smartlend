package com.smartlend.notification.channel.email;

import com.smartlend.notification.channel.NotificationPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ses.SesClient;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SesEmailChannelTest {

    @Mock
    SesClient sesClient;

    SesEmailChannel channel;

    NotificationPayload payload() {
        return new NotificationPayload(
                "LOAN_APPROVED", "user@example.com", "Test User",
                "9999999999", "Loan Approved", "Plain body",
                "<h1>Loan Approved</h1>", Map.of("loanId", "loan-1")
        );
    }

    @BeforeEach
    void setUp() {
        channel = new SesEmailChannel();
        ReflectionTestUtils.setField(channel, "sesClient",  sesClient);
        ReflectionTestUtils.setField(channel, "fromEmail",  "noreply@smartlend.com");
        ReflectionTestUtils.setField(channel, "fromName",   "SmartLend");
        ReflectionTestUtils.setField(channel, "enabled",    true);
    }

    @Test
    void send_htmlEmail_callsSesClient() {
        channel.send(payload());
        verify(sesClient).sendEmail(any(Consumer.class));
    }

    @Test
    void isEnabled_false_channelDisabled() {
        ReflectionTestUtils.setField(channel, "enabled", false);
        assertThat(channel.isEnabled()).isFalse();
        channel.send(payload()); // should be no-op when disabled check is callers's job
        // dispatcher skips send() when isEnabled()==false; here we verify isEnabled returns false
        assertThat(channel.channelName()).isEqualTo("EMAIL_SES");
    }

    @Test
    void send_sesThrows_propagatesException() {
        doThrow(new RuntimeException("SES network error"))
                .when(sesClient).sendEmail(any(Consumer.class));
        assertThatThrownBy(() -> channel.send(payload()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SES network error");
    }
}
