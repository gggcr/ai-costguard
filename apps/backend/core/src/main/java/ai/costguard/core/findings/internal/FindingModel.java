package ai.costguard.core.findings.internal;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

enum FindingStatus { new_, review, accepted, in_progress, confirmed, rejected, postponed, auto_closed;

    String db() {
        return this == new_ ? "new" : name();
    }

    static FindingStatus of(String db) {
        return "new".equals(db) ? new_ : valueOf(db);
    }
}

/** People's actions. {@code autoClose}, {@code reopen} and {@code dateReached} come from the system only. */
enum FindingAction { assign, accept, postpone, reject, createTask, confirm, missInvoice }

/** The board's life cycle, frame 8; the same table as FINDING_TRANSITIONS in the frontend. */
final class Transitions {

    static final Map<FindingStatus, Set<FindingAction>> ALLOWED = Map.of(
            FindingStatus.new_, EnumSet.of(FindingAction.assign),
            FindingStatus.review, EnumSet.of(FindingAction.accept, FindingAction.postpone, FindingAction.reject),
            FindingStatus.accepted, EnumSet.of(FindingAction.createTask),
            FindingStatus.in_progress, EnumSet.of(FindingAction.confirm, FindingAction.missInvoice),
            FindingStatus.postponed, EnumSet.noneOf(FindingAction.class),
            FindingStatus.auto_closed, EnumSet.noneOf(FindingAction.class),
            FindingStatus.rejected, EnumSet.noneOf(FindingAction.class),
            FindingStatus.confirmed, EnumSet.noneOf(FindingAction.class));

    /** Statuses a finding may close from by itself when its condition disappears. */
    static final Set<FindingStatus> AUTO_CLOSABLE = EnumSet.of(FindingStatus.new_, FindingStatus.review);

    private Transitions() {
    }
}

record FindingEvent(Instant at, String by, String text) {
}

record Finding(UUID id, String rule, String fingerprint, String title, String description, UUID subscriptionId,
        UUID agentId, BigDecimal expectedSavings, String status, UUID ownerId, LocalDate postponedUntil,
        String rejectReason, int missedInvoices, Instant createdAt, List<FindingEvent> history) {
}

record ActionRequest(@NotNull FindingAction action, UUID ownerId, LocalDate date, String reason) {
}

record DueFinding(UUID findingId, UUID tenantId) {
}

record FindingTotals(int open, BigDecimal expectedSavings, BigDecimal confirmedSavings) {
}
