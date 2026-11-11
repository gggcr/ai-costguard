package ai.costguard.core.cabinet.internal;

import ai.costguard.core.assets.AssetIssued;
import ai.costguard.core.notifications.PersonalNotices;
import java.time.format.DateTimeFormatter;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** A device issued to an employee: they are told, and the act is in their documents (ADR 0052). */
@Component
class AssetNotices {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final PersonalNotices notices;

    AssetNotices(PersonalNotices notices) {
        this.notices = notices;
    }

    @ApplicationModuleListener
    void on(AssetIssued e) {
        notices.send(e.tenantId(), e.employeeId(), "Вам выдана техника: " + e.model() + ", инв. № "
                + e.inventoryNumber() + ", " + DAY.format(e.issuedOn()) + ". Акт " + e.actNumber()
                + " — в разделе «Документы»", "/cabinet/documents", "info");
    }
}
