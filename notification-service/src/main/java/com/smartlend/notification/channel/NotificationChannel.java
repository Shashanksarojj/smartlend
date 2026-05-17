package com.smartlend.notification.channel;

/**
 * Extension point for notification delivery mechanisms.
 * Add a new channel by implementing this interface and annotating with @Component.
 * The NotificationDispatcher auto-discovers all active channels.
 */
public interface NotificationChannel {

    /** Unique identifier for this channel (e.g. "EMAIL_SENDGRID", "SMS_TWILIO"). */
    String channelName();

    /** When false the dispatcher skips this channel entirely. */
    boolean isEnabled();

    /** Deliver the notification. Throw any exception to signal failure — dispatcher will log and continue. */
    void send(NotificationPayload payload);
}
