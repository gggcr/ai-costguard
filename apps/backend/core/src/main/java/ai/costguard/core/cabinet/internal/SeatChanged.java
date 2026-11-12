package ai.costguard.core.cabinet.internal;

import java.util.UUID;

/** A seat of a subscription was given to a person or taken back into the pool (ADR 0065). */
record SeatChanged(UUID tenantId, UUID subscriptionId, UUID employeeId, boolean granted) {
}
