package ai.costguard.core.notifications;

import java.util.UUID;

/**
 * A notification for one employee (ADR 0052): their feed in «Мой кабинет», and their e-mail and Telegram as they
 * chose. It never enters the company's feed. Joins the caller's transaction.
 */
public interface PersonalNotices {

    /** {@code level}: info, warning, critical or good. */
    void send(UUID tenantId, UUID employeeId, String text, String link, String level);
}
