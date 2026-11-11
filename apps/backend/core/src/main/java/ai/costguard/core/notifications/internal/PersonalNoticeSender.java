package ai.costguard.core.notifications.internal;

import ai.costguard.core.notifications.PersonalNotices;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PersonalNoticeSender implements PersonalNotices {

    private final NotificationService service;

    PersonalNoticeSender(NotificationService service) {
        this.service = service;
    }

    @Override
    public void send(UUID tenantId, UUID employeeId, String text, String link, String level) {
        service.notifyPersonal(tenantId, employeeId, text, link, Level.valueOf(level));
    }
}
