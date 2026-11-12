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

/**
 * «Мои документы» (ADR 0052): what the employer uploads for an employee, and the employee's handover acts. A PDF can
 * be sent for signing (ADR 0068): the employee, the employer and the accountant each sign it with a code of their
 * signing key, and every download then carries the stamps.
 */
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
    private final SigningKeys keys;

    DocumentService(DocumentRepository documents, EmployeeActs acts, StoredFiles files, PersonalNotices notices,
            EmployeeDirectory employees, Whoami whoami, TenantSession session, ApplicationEventPublisher events,
            Clock clock, SigningKeys keys) {
        this.keys = keys;
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
            String contentType, byte[] content, List<SignerParty> signers) {
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
        List<String> signers = upload.signers() == null ? List.of()
                : upload.signers().stream().distinct().sorted().map(Enum::name).toList();
        if (!signers.isEmpty() && !SignatureStamp.isPdf(upload.content())) {
            throw CabinetProblem.SIGN_ONLY_PDF.failure();
        }
        UUID id = UUID.randomUUID();
        String key = tenantId + "/documents/" + id;
        files.put(key, upload.content());
        StoredDocument d = new StoredDocument(id, employeeId, upload.kind().name(), upload.title().strip(),
                blank(upload.number()), upload.signedOn(), key, upload.fileName(), upload.contentType(),
                upload.content().length, by, clock.instant(), signers);
        documents.insert(tenantId, d);
        boolean mine = signers.contains("employee");
        notices.send(tenantId, employeeId, (mine ? "Подпишите документ: " : "Новый документ: ") + d.title(),
                "/cabinet/documents", mine ? "warning" : "info");
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
        if (!documents.signatures(d.id()).isEmpty()) {
            throw CabinetProblem.DOCUMENT_SIGNED.failure();
        }
        documents.delete(d.id());
        files.delete(d.fileKey());
        events.publishEvent(ActionTaken.of(tenantId, "document.removed", "employee_document", d.id(),
                ActionTaken.details("employeeId", employeeId)));
    }

    /** A signed PDF comes with its stamps; the original stays in storage as it was. */
    private DocumentFile content(StoredDocument d) {
        byte[] bytes = files.get(d.fileKey()).orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        List<DocumentSignature> signed = documents.signatures(d.id());
        if (!signed.isEmpty() && SignatureStamp.isPdf(bytes)) {
            return new DocumentFile(d.fileName(), "application/pdf", SignatureStamp.stamp(bytes, d.title(), d.id(),
                    signed));
        }
        return new DocumentFile(d.fileName(), d.contentType(), bytes);
    }

    /** The employee signs their own document. */
    @Transactional
    EmployeeDocument signAsEmployee(UUID tenantId, Jwt jwt, UUID documentId, String code) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        StoredDocument d = documents.lock(documentId).filter(x -> x.employeeId().equals(me))
                .orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        return sign(tenantId, jwt, d, SignerParty.employee, code);
    }

    /** The employer (owner) or the accountant (finance) signs; the controller checks the role. */
    @Transactional
    EmployeeDocument signAs(UUID tenantId, Jwt jwt, UUID documentId, SignerParty party, String code) {
        session.bind(tenantId);
        StoredDocument d = documents.lock(documentId).orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        return sign(tenantId, jwt, d, party, code);
    }

    private EmployeeDocument sign(UUID tenantId, Jwt jwt, StoredDocument d, SignerParty party, String code) {
        if (!d.signers().contains(party.name())) {
            throw CabinetProblem.SIGN_NOT_ASKED.failure();
        }
        List<DocumentSignature> before = documents.signatures(d.id());
        if (before.stream().anyMatch(s -> s.party().equals(party.name()))) {
            throw CabinetProblem.SIGN_ALREADY.failure();
        }
        // the signer is the account of the token's subject; the stamp names it as the company stored it, not as
        // the token (whose name and e-mail a person can change in Keycloak)
        var signer = keys.account(jwt);
        String method = keys.check(tenantId, signer.id(), code);
        byte[] bytes = files.get(d.fileKey()).orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        DocumentSignature signature = new DocumentSignature(party.name(), signer.name(), signer.email(), method,
                sha256(bytes), clock.instant());
        documents.sign(tenantId, d.id(), signature, signer.id());
        int left = d.signers().size() - before.size() - 1;
        if (left == 0) {
            notices.send(tenantId, d.employeeId(), "Документ подписан всеми сторонами: " + d.title(),
                    "/cabinet/documents", "good");
        } else if (party != SignerParty.employee) {
            notices.send(tenantId, d.employeeId(), SignatureStamp.party(party.name()) + " подписал(а) документ: "
                    + d.title(), "/cabinet/documents", "info");
        }
        events.publishEvent(ActionTaken.of(tenantId, "document.signed", "employee_document", d.id(),
                ActionTaken.details("party", party.name(), "method", method, "sha256", signature.documentSha256())));
        return view(d);
    }

    /** What the employer or the accountant still has to sign. */
    @Transactional(readOnly = true)
    List<AwaitingSignature> awaiting(UUID tenantId, List<SignerParty> parties) {
        session.bind(tenantId);
        List<String> names = parties.stream().map(Enum::name).toList();
        List<AwaitingSignature> out = new ArrayList<>();
        if (names.isEmpty()) {
            return out;
        }
        for (StoredDocument d : documents.awaiting(names)) {
            List<DocumentSignature> signed = documents.signatures(d.id());
            String name = employees.card(d.employeeId()).map(EmployeeDirectory.EmployeeCard::name).orElse("");
            for (String p : d.signers()) {
                if (names.contains(p) && signed.stream().noneMatch(s -> s.party().equals(p))) {
                    out.add(new AwaitingSignature(d.id(), d.employeeId(), name, d.title(), p, d.createdAt(),
                            d.signers(), signed));
                }
            }
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<EmployeeDocument> all(UUID employeeId) {
        List<EmployeeDocument> out = new ArrayList<>();
        documents.of(employeeId).forEach(d -> out.add(view(d)));
        for (EmployeeActs.Act a : acts.actsOf(employeeId)) {
            out.add(new EmployeeDocument(a.id(), "asset_act", "asset_act",
                    "Акт выдачи: " + a.model() + " (инв. " + a.inventoryNumber() + ")", a.number(), a.issuedOn(),
                    "Акт " + a.number() + ".pdf", null, a.issuedOn().atStartOfDay(java.time.ZoneOffset.UTC)
                            .toInstant(), a.returnedOn(), List.of(), List.of()));
        }
        out.sort(Comparator.comparing(EmployeeDocument::createdAt).reversed());
        return out;
    }

    private EmployeeDocument view(StoredDocument d) {
        return new EmployeeDocument(d.id(), "upload", d.kind(), d.title(), d.number(), d.signedOn(), d.fileName(),
                d.size(), d.createdAt(), null, d.signers(), documents.signatures(d.id()));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
