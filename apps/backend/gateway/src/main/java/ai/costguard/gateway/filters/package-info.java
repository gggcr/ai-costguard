/**
 * The gateway check ladder, seven steps. Cheap checks run on every operation, expensive ones only on large ones.
 * The order is defined by {@code getOrder()} on each filter.
 */
package ai.costguard.gateway.filters;
