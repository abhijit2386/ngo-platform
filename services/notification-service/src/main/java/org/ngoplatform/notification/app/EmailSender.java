package org.ngoplatform.notification.app;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.ngoplatform.notification.domain.Delivery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** SMTP adapter. Local development points at Mailpit (localhost:1025); production at the provider's relay. */
@Component
public class EmailSender implements ChannelSender {
    private final JavaMailSender mail;
    private final String from;
    private final String fromName;

    public EmailSender(JavaMailSender mail, @Value("${ngo.notifications.mail-from}") String from,
                       @Value("${ngo.notifications.mail-from-name}") String fromName) {
        this.mail = mail; this.from = from; this.fromName = fromName;
    }

    @Override
    public String channel() { return "EMAIL"; }

    @Override
    public void send(Delivery d) throws Exception {
        MimeMessage msg = mail.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
        h.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
        h.setTo(d.getRecipient());
        h.setSubject(d.getSubject() == null ? "" : d.getSubject());
        h.setText(d.getBody(), false);
        msg.setHeader("X-Notification-Id", d.getId().toString());
        mail.send(msg);
    }
}
