/**
 * The gateway check ladder, seven steps. Cheap checks run on every operation, expensive ones only on large ones.
 * The order is defined by {@code getOrder()} on each step; {@link ai.costguard.gateway.ladder.CheckLadder} runs them.
 */
package ai.costguard.gateway.filters;
