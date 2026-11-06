/**
 * Employee equipment: who holds what and when it is due back. Owns ASSET, ASSET_ASSIGNMENT.
 * Not a warehouse and not fixed-asset accounting: those stay in the accounting system.
 */
@ApplicationModule(displayName = "Assets")
package ai.costguard.core.assets;

import org.springframework.modulith.ApplicationModule;
