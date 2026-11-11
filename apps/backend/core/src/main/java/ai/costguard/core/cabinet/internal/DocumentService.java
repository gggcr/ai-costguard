package ai.costguard.core.cabinet.internal;

import ai.costguard.core.assets.EmployeeActs;
import ai.costguard.core.imports.StoredFiles;
import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** «Мои документы» (ADR 0052): what the employer uploads for an employee, and the employee's handover acts. */
@Service
class DocumentService {

    static final long MAX_SIZE = 20L * 1024 * 1024;

    private final DocumentRepository documents;
    private final EmployeeActs acts;
    private final StoredFiles files;
    private final PersonalNotices notices;
    private final EmployeeDirectory employees;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DocumentService(DocumentRepository documents, EmployeeActs acts, StoredFiles files, PersonalNotices notices,
            EmployeeDirectory employees, Whoami whoami, TenantSession session, ApplicationEventPublisher events,
            Clock clock) {
        this.documents = documents;
        this.acts = acts;
        this.files = files;
        this.notices = notices;
        this.employees = employees;
        this.whoami = whoami;
        this.session = session;
        this.events = events;
        this.clock = clock;
    }

    record Upload(DocumentKind kind, String title, String number, LocalDate signedOn, String fileName,
            String contentType, byte[] content) {
    }

    /** The employer adds a document; the employee is told in their cabinet and over their channels. */
    @Transactional
    EmployeeDocument add(UUID tenantId, UUID employeeId, Upload upload, String by) {
        session.bind(tenantId);
        if (upload.content().length == 0) {
            throw CabinetProblem.FILE_EMPTY.failure();
        }
        if (upload.content().length > MAX_SIZE) {
            throw CabinetProblem.FILE_TOO_LARGE.failure();
        }
        employees.card(employeeId).orElseThrow(CabinetProblem.EMPLOYEE_NOT_FOUND::failure);
        UUID id = UUID.randomUUID();
        String key = tenantId + "/documents/" + id;
        files.put(key, upload.content());
        StoredDocument d = new StoredDocument(id, employeeId, upload.kind().name(), upload.title().strip(),
                blank(upload.number()), upload.signedOn(), key, upload.fileName(), upload.contentType(),
                upload.content().length, by, clock.instant());
        documents.insert(tenantId, d);
        notices.send(tenantId, employeeId, "Новый документ: " + d.title(), "/cabinet/documents", "info");
        events.publishEvent(ActionTaken.of(tenantId, "document.added", "employee_document", id,
                ActionTaken.details("employeeId", employeeId, "kind", d.kind())));
        return view(d);
    }

    @Transactional(readOnly = true)
    List<EmployeeDocument> of(UUID tenantId, UUID employeeId) {
        session.bind(tenantId);
        employees.card(employeeId).orElseThrow(CabinetProblem.EMPLOYEE_NOT_FOUND::failure);
        return all(employeeId);
    }

    @Transactional(readOnly = true)
    List<EmployeeDocument> mine(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        return all(whoami.employee(jwt));
    }

    @Transactional(readOnly = true)
    DocumentFile myFile(UUID tenantId, Jwt jwt, UUID documentId) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        var uploaded = documents.find(documentId).filter(d -> d.employeeId().equals(me));
        if (uploaded.isPresent()) {
            return content(uploaded.get());
        }
        return acts.file(me, documentId)
                .map(a -> new DocumentFile("Акт " + a.number() + ".pdf", "application/pdf", a.pdf()))
                .orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
    }

    @Transactional(readOnly = true)
    DocumentFile file(UUID tenantId, UUID employeeId, UUID documentId) {
        session.bind(tenantId);
        return documents.find(documentId).filter(d -> d.employeeId().equals(employeeId)).map(this::content)
                .orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
    }

    @Transactional
    void remove(UUID tenantId, UUID employeeId, UUID documentId) {
        session.bind(tenantId);
        StoredDocument d = documents.find(documentId).filter(x -> x.employeeId().equals(employeeId))
                .orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        documents.delete(d.id());
        files.delete(d.fileKey());
        events.publishEvent(ActionTaken.of(tenantId, "document.removed", "employee_document", d.id(),
                ActionTaken.details("employeeId", employeeId)));
    }

    private DocumentFile content(StoredDocument d) {
        byte[] bytes = files.get(d.fileKey()).orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        return new DocumentFile(d.fileName(), d.contentType(), bytes);
    }

    private List<EmployeeDocument> all(UUID employeeId) {
        List<EmployeeDocument> out = new ArrayList<>();
        documents.of(employeeId).forEach(d -> out.add(view(d)));
        for (EmployeeActs.Act a : acts.actsOf(employeeId)) {
            out.add(new EmployeeDocument(a.id(), "asset_act", "asset_act",
                    "Акт выдачи: " + a.model() + " (инв. " + a.inventoryNumber() + ")", a.number(), a.issuedOn(),
                    "Акт " + a.number() + ".pdf", null, a.issuedOn().atStartOfDay(java.time.ZoneOffset.UTC)
                            .toInstant(), a.returnedOn()));
        }
        out.sort(Comparator.comparing(EmployeeDocument::createdAt).reversed());
        return out;
    }

    private static EmployeeDocument view(StoredDocument d) {
        return new EmployeeDocument(d.id(), "upload", d.kind(), d.title(), d.number(), d.signedOn(), d.fileName(),
                d.size(), d.createdAt(), null);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
