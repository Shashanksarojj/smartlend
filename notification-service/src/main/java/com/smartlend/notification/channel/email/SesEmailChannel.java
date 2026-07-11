package com.smartlend.notification.channel.email;

import com.smartlend.notification.channel.NotificationChannel;
import com.smartlend.notification.channel.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;

@Component
@Slf4j
public class SesEmailChannel implements NotificationChannel {

    @Autowired(required = false)
    private SesClient sesClient;

    @Value("${aws.ses.from-email:noreply@smartlend.com}")
    private String fromEmail;

    @Value("${aws.ses.from-name:SmartLend}")
    private String fromName;

    @Value("${notification.channels.ses.enabled:false}")
    private boolean enabled;

    @Override
    public String channelName() {
        return "EMAIL_SES";
    }

    @Override
    public boolean isEnabled() {
        // Belt-and-suspenders: disabled if the SesClient bean was not created
        if (sesClient == null) {
            return false;
        }
        return enabled;
    }

    @Override
    public void send(NotificationPayload payload) {
        if (sesClient == null) {
            log.warn("SesEmailChannel.send() called but SesClient is null — skipping");
            return;
        }

        // Prefer HTML body; fall back to plain text if absent
        String htmlBody = (payload.htmlBody() != null && !payload.htmlBody().isBlank())
                ? payload.htmlBody()
                : payload.body();

        sesClient.sendEmail(r -> r
                .destination(d -> d.toAddresses(payload.recipientEmail()))
                .message(m -> m
                        .subject(c -> c.data(payload.subject()))
                        .body(b -> b
                                .html(c -> c.data(htmlBody))
                                .text(c -> c.data(payload.body()))))
                .source(fromEmail));

        log.info("SES email sent — to={} subject={}", payload.recipientEmail(), payload.subject());
    }
}
