package ai.costguard.core.cabinet.internal;

import java.util.List;
import java.util.UUID;

/**
 * Who the token is. {@code userId} is empty for a person who signs in without a CostGuard user record (an employee
 * known only by e-mail); {@code employee} is empty for a user who is not an employee of the company.
 */
record Me(UUID userId, String name, String email, List<String> roles, Profile employee) {
}

record Profile(UUID id, String name, String position, String department, String email) {
}
