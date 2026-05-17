package com.smartlend.notification.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dispatches a NotificationPayload to every enabled channel.
 * Channels are auto-discovered via Spring DI — no changes needed here when adding new ones.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private final List<NotificationChannel> channels;

    public void dispatch(NotificationPayload payload) {
        log.info("Dispatching notification [{}] to recipient={}", payload.eventType(), payload.recipientEmail());

        channels.stream()
                .filter(NotificationChannel::isEnabled)
                .forEach(channel -> {
                    try {
                        channel.send(payload);
                        log.info("Notification delivered via {} for event={}", channel.channelName(), payload.eventType());
                    } catch (Exception e) {
                        log.error("Channel {} failed for event={}: {}", channel.channelName(), payload.eventType(), e.getMessage());
                    }
                });
    }
}
