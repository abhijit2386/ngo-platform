package org.ngoplatform.identity.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY: "sends" the SMS to the local Mailpit inbox (to +91XXXXXXXXXX@sms.local) so codes can be read
 * at http://localhost:8025 without an SMS provider. Never enable in production.
 */
@Component
@ConditionalOnProperty(name = "ngo.sms.provider", havingValue = "dev-mailbox", matchIfMissing = true)
public class DevMailboxSmsSender implements SmsSender {
    private static final Logger log = LoggerFactory.getLogger(DevMailboxSmsSender.class);
    private final JavaMailSender mail;

    public DevMailboxSmsSender(JavaMailSender mail) { this.mail = mail; }

    @Override
    public void sendOtp(String phone10, String code) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom("sms-gateway@sms.local");
        m.setTo("+91" + phone10 + "@sms.local");
        m.setSubject("SMS to +91 " + phone10.substring(0, 2) + "XXXXXX" + phone10.substring(8));
        m.setText(SmsSender.otpText(code));
        mail.send(m);
        log.info("dev SMS delivered to the local mailbox for {}XXXXXX{}", phone10.substring(0, 2), phone10.substring(8));
    }
}
