package com.smartlend.notification.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    private NotificationPayload payload() {
        return new NotificationPayload(
                "LOAN_APPROVED", "user@example.com", "Test User",
                "9999999999", "Subject", "Body", "<b>Body</b>",
                Map.of("loanId", "loan-1")
        );
    }

    @Test
    void dispatch_callsSendOnEnabledChannels() {
        NotificationChannel enabled = mock(NotificationChannel.class);
        when(enabled.isEnabled()).thenReturn(true);

        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(enabled));
        dispatcher.dispatch(payload());

        verify(enabled).send(any(NotificationPayload.class));
    }

    @Test
    void dispatch_skipsDisabledChannels() {
        NotificationChannel disabled = mock(NotificationChannel.class);
        when(disabled.isEnabled()).thenReturn(false);

        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(disabled));
        dispatcher.dispatch(payload());

        verify(disabled, never()).send(any());
    }

    @Test
    void dispatch_continuesAfterChannelFailure() {
        NotificationChannel failing = mock(NotificationChannel.class);
        NotificationChannel healthy = mock(NotificationChannel.class);
        when(failing.isEnabled()).thenReturn(true);
        when(healthy.isEnabled()).thenReturn(true);
        when(failing.channelName()).thenReturn("FAILING");
        doThrow(new RuntimeException("network error")).when(failing).send(any());

        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(failing, healthy));
        dispatcher.dispatch(payload());

        verify(healthy).send(any(NotificationPayload.class));
    }

    @Test
    void dispatch_withNoChannels_doesNotThrow() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of());
        assertThatCode(() -> dispatcher.dispatch(payload())).doesNotThrowAnyException();
    }
}