package com.smartlend.notification.channel.sms;

import com.smartlend.notification.channel.NotificationChannel;
import com.smartlend.notification.channel.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SMS channel stub — integrate Twilio here when ready.
 * Enable via NOTIFICATION_SMS_ENABLED=true and populate TWILIO_* env vars.
 */
@Component
@Slf4j
public class SmsChannel implements NotificationChannel {

    private final boolean enabled;

    public SmsChannel(@Value("${notification.channels.sms.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String channelName() {
        return "SMS_TWILIO";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationPayload payload) {
        // TODO: integrate Twilio SDK
        // TwilioClient.send(payload.recipientPhone(), payload.body());
        log.info("[SMS STUB] Would send to {} — event={}", payload.recipientEmail(), payload.eventType());
    }
}
