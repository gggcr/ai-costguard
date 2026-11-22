package ai.costguard.core.imports.internal;

import ai.costguard.core.imports.ImportAccepted;
import ai.costguard.core.imports.ImportFileUploaded;
import ai.costguard.core.imports.ImportResults;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.UserDirectory;
import ai.costguard.platform.tenant.TenantSession;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ImportService implements ImportResults {

    private final ImportRepository imports;
    private final FileStore files;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final UserDirectory users;
    private final EmployeeDirectory company;

    ImportService(ImportRepository imports, FileStore files, TenantSession session, Clock clock,
            ApplicationEventPublisher events, UserDirectory users, EmployeeDirectory company) {
        this.imports = imports;
        this.files = files;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.users = users;
        this.company = company;
    }

    /** The company of the bound tenant: how its files are read (ADR 0072). Empty values until it is saved. */
    private EmployeeDirectory.CompanyCard card() {
        return company.company().orElse(new EmployeeDirectory.CompanyCard(null, null, null, null));
    }

    record Upload(ImportJob job, boolean created) {
    }

    /** The same file uploaded again returns the first import, so it is never counted twice. */
    @Transactional
    Upload upload(UUID tenantId, String fileName, String contentType, byte[] content, UUID vendorId,
            String subject, String tokenBy) {
        if (content.length == 0) {
            throw ImportProblem.EMPTY_FILE.failure();
        }
        ImportType type = ImportType.of(fileName).orElseThrow(ImportProblem.UNSUPPORTED_FILE::failure);
        session.bind(tenantId);
        // who uploaded, as stored with the file: the account of the token's subject, not the token's e-mail, which
        // the person can change in Keycloak and which nothing else records (audit of 2026-11-13)
        String by = users.userBySubject(subject).map(UserDirectory.UserCard::email).orElse(tokenBy);
        String sha = sha256(content);
        var existing = imports.bySha(sha);
        if (existing.isPresent()) {
            return new Upload(existing.get(), false);
        }
        String key = tenantId + "/" + sha;
        files.put(key, new ByteArrayInputStream(content));
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        if (!imports.insert(tenantId, id, fileName, type, vendorId, now, by, sha, key, content.length)) {
            return new Upload(imports.bySha(sha).orElseThrow(), false);
        }
        var card = card();
        events.publishEvent(new ImportFileUploaded(id, tenantId, key, sha, fileName, contentType, vendorId,
                now.toInstant(), card.country(), card.currency()));
        return new Upload(imports.find(id).orElseThrow(), true);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(readOnly = true)
    List<ImportJob> list(UUID tenantId) {
        session.bind(tenantId);
        return imports.list();
    }

    @Transactional(readOnly = true)
    ImportJob get(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return imports.find(id).orElseThrow(ImportProblem.IMPORT_NOT_FOUND::failure);
    }

    @Override
    @Transactional
    public void parsed(UUID tenantId, UUID fileId, List<String> columns, Map<String, String> suggested, int rows,
            BigDecimal total, List<ImportResults.RowError> errors, List<Uncertain> uncertain) {
        session.bind(tenantId);
        ImportJob job = imports.lock(fileId).orElseThrow(ImportProblem.IMPORT_NOT_FOUND::failure);
        if (job.status() != ImportStatus.uploaded) {
            return;
        }
        imports.parsed(fileId, columns, suggested, rows, total,
                errors.stream().map(e -> new ai.costguard.core.imports.internal.RowError(e.row(), e.message()))
                        .toList());
        uncertain.forEach(u -> imports.insertTask(tenantId, fileId, u.row(), u.field(), u.value(), u.confidence()));
    }

    @Override
    @Transactional
    public void failed(UUID tenantId, UUID fileId, String reason) {
        session.bind(tenantId);
        imports.lock(fileId).filter(j -> j.status() != ImportStatus.done)
                .ifPresent(j -> imports.setStatus(fileId, ImportStatus.failed, reason));
    }

    @Transactional
    ImportJob confirmMapping(UUID tenantId, UUID id, MappingRequest request) {
        session.bind(tenantId);
        ImportJob job = imports.lock(id).orElseThrow(ImportProblem.IMPORT_NOT_FOUND::failure);
        if (job.status() != ImportStatus.mapping) {
            throw ImportProblem.WRONG_STAGE.failure();
        }
        if (!FocusFields.ALL.containsAll(request.mapping().values())
                || !job.columns().containsAll(request.mapping().keySet())) {
            throw ImportProblem.UNKNOWN_FIELD.failure();
        }
        boolean open = job.reviewTasks().stream().anyMatch(t -> !t.resolved());
        imports.setMapping(id, request.mapping(), request.keepTemplate(), open ? ImportStatus.review : ImportStatus.done);
        ImportJob updated = imports.find(id).orElseThrow();
        if (!open) {
            accept(tenantId, updated);
        }
        return updated;
    }

    @Transactional
    ImportJob resolve(UUID tenantId, UUID id, UUID taskId, String value) {
        session.bind(tenantId);
        ImportJob job = imports.lock(id).orElseThrow(ImportProblem.IMPORT_NOT_FOUND::failure);
        if (job.status() != ImportStatus.review && job.status() != ImportStatus.mapping) {
            throw ImportProblem.WRONG_STAGE.failure();
        }
        if (!imports.resolve(id, taskId, value)) {
            throw ImportProblem.TASK_NOT_FOUND.failure();
        }
        ImportJob updated = imports.find(id).orElseThrow();
        if (updated.status() == ImportStatus.review && updated.reviewTasks().stream().allMatch(ReviewTask::resolved)) {
            imports.setStatus(id, ImportStatus.done, null);
            updated = imports.find(id).orElseThrow();
            accept(tenantId, updated);
        }
        return updated;
    }

    private void accept(UUID tenantId, ImportJob job) {
        Map<Integer, Map<String, String>> corrections = new HashMap<>();
        job.reviewTasks().forEach(t -> corrections.computeIfAbsent(t.row(), r -> new HashMap<>())
                .put(t.field(), t.resolvedValue()));
        var card = card();
        events.publishEvent(new ImportAccepted(tenantId, job.id(), tenantId + "/" + job.sha256(), job.fileName(),
                job.vendorId(), job.mapping(), corrections, clock.instant(), card.country(), card.currency()));
    }
}
