/**
 * Employee equipment: who holds what, when it is due back, and the handover act of each issue.
 * Owns ASSET, ASSET_ASSIGNMENT, ASSET_ACT.
 * Not a warehouse and not fixed-asset accounting: those stay in the accounting system.
 */
@ApplicationModule(displayName = "Assets")
package ai.costguard.core.assets;

import org.springframework.modulith.ApplicationModule;
