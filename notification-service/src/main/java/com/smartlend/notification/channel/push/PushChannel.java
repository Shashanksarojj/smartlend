package com.smartlend.notification.channel.push;

import com.smartlend.notification.channel.NotificationChannel;
import com.smartlend.notification.channel.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Push notification channel stub — integrate Firebase FCM here when ready.
 * Enable via NOTIFICATION_PUSH_ENABLED=true and populate FIREBASE_* env vars.
 */
@Component
@Slf4j
public class PushChannel implements NotificationChannel {

    private final boolean enabled;

    public PushChannel(@Value("${notification.channels.push.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String channelName() {
        return "PUSH_FIREBASE";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationPayload payload) {
        // TODO: integrate Firebase Admin SDK
        // FirebaseMessaging.getInstance().send(Message.builder()...build());
        log.info("[PUSH STUB] Would send to {} — event={}", payload.recipientEmail(), payload.eventType());
    }
}
