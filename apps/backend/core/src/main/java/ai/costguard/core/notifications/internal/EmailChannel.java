package ai.costguard.core.notifications.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** E-mail over SMTP ({@code spring.mail.*}). Off while {@code MAIL_HOST} is empty. */
@Component
class EmailChannel implements Channel {

    private static final int SUBJECT = 120;

    private final ObjectProvider<JavaMailSender> mail;
    private final String from;

    EmailChannel(ObjectProvider<JavaMailSender> mail, @Value("${costguard.notifications.mail-from:}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return mail.getIfAvailable() != null && !from.isBlank();
    }

    @Override
    public void send(Message message, String address) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            throw new DeliveryFailure("E-mail is not configured", true);
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(address);
        String subject = "CostGuard: " + message.text();
        m.setSubject(subject.length() > SUBJECT ? subject.substring(0, SUBJECT - 1) + "…" : subject);
        m.setText(message.text() + (message.url() == null ? "" : "\n\nОткрыть в CostGuard: " + message.url())
                + "\n\nНастроить уведомления: CostGuard → Уведомления.");
        try {
            sender.send(m);
        } catch (MailParseException e) {
            throw new DeliveryFailure("Bad e-mail address", true);
        } catch (MailException e) {
            throw new DeliveryFailure("SMTP: " + e.getClass().getSimpleName(), false);
        }
    }
}
