package ai.costguard.core.cabinet.internal;

import java.util.List;

/** {@code unmatchedApps}: programs people signed in to that are not a subscription of the company. */
record MicrosoftActivityResult(int signIns, int licensesUpdated, List<String> unmatchedApps) {
}
