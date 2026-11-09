package ai.costguard.core.privacy.internal;

import ai.costguard.core.privacy.CompactedRecordsErased;
import ai.costguard.core.privacy.Report;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The {@link Report} the holders fill. Deletions of nothing are left out, so the record stays short. */
final class Steps implements Report {

    private final List<Step> steps = new ArrayList<>();
    private final List<CompactedRecordsErased.Key> tombstones = new ArrayList<>();

    @Override
    public void deleted(String store, long rows) {
        if (rows > 0) {
            steps.add(new Step(store, StepAction.deleted, rows, null, null));
        }
    }

    /** A store that deletes without saying how many rows: ClickHouse, Keycloak by query. */
    void erasedAll(String store, String note) {
        steps.add(new Step(store, StepAction.deleted, 0, note, null));
    }

    @Override
    public void anonymized(String store, long rows) {
        if (rows > 0) {
            steps.add(new Step(store, StepAction.anonymized, rows, null, null));
        }
    }

    @Override
    public void retained(String store, long rows, String why, LocalDate until) {
        steps.add(new Step(store, StepAction.retained, rows, why, until == null ? null : until.toString()));
    }

    @Override
    public void manual(String store, String what) {
        steps.add(new Step(store, StepAction.manual, 0, what, null));
    }

    @Override
    public void tombstone(String topic, String key) {
        tombstones.add(new CompactedRecordsErased.Key(topic, key));
    }

    List<Step> steps() {
        return List.copyOf(steps);
    }

    List<CompactedRecordsErased.Key> tombstones() {
        return List.copyOf(tombstones);
    }

    long total(StepAction action) {
        return steps.stream().filter(s -> s.action() == action).mapToLong(Step::rows).sum();
    }
}
