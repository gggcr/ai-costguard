/** Ed25519-signed receipts and the hash chain. Owns RECEIPT, ANCHOR_BATCH. Operations live in ClickHouse; a receipt refers to one by operation_id. */
@ApplicationModule(displayName = "Journal")
package ai.costguard.core.journal;

import org.springframework.modulith.ApplicationModule;
