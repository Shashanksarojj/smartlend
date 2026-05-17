package com.smartlend.notification.channel.email;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.smartlend.notification.channel.NotificationChannel;
import com.smartlend.notification.channel.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class SendGridEmailChannel implements NotificationChannel {

    private final SendGrid sendGrid;
    private final Email fromEmail;
    private final boolean enabled;

    public SendGridEmailChannel(
            @Value("${sendgrid.api-key}") String apiKey,
            @Value("${sendgrid.from-email}") String fromEmailAddress,
            @Value("${sendgrid.from-name}") String fromName,
            @Value("${notification.channels.email.enabled:true}") boolean enabled) {
        this.sendGrid = new SendGrid(apiKey);
        this.fromEmail = new Email(fromEmailAddress, fromName);
        this.enabled = enabled;
    }

    @Override
    public String channelName() {
        return "EMAIL_SENDGRID";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationPayload payload) {
        Email to = new Email(payload.recipientEmail(), payload.recipientName());

        boolean hasHtml = payload.htmlBody() != null && !payload.htmlBody().isBlank();
        Content content = hasHtml
                ? new Content("text/html", payload.htmlBody())
                : new Content("text/plain", payload.body());

        Mail mail = new Mail(fromEmail, payload.subject(), to, content);

        Request request = new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            var response = sendGrid.api(request);

            if (response.getStatusCode() >= 400) {
                throw new RuntimeException("SendGrid returned HTTP " + response.getStatusCode()
                        + ": " + response.getBody());
            }
            log.debug("SendGrid accepted email to={} subject={}", payload.recipientEmail(), payload.subject());
        } catch (IOException e) {
            throw new RuntimeException("SendGrid I/O error: " + e.getMessage(), e);
        }
    }
}
