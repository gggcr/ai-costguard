/**
 * Receipts of allowed operations: Ed25519 signature and a hash chain per tenant, anchored in NEAR in batches.
 * Owns RECEIPT, ANCHOR_BATCH.
 */
@ApplicationModule(displayName = "Journal")
package ai.costguard.core.journal;

import org.springframework.modulith.ApplicationModule;
