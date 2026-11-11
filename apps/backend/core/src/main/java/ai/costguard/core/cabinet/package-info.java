/**
 * The employee's own cabinet («Мой кабинет», ADR 0051): who I am, and later my documents, vacations, pay, licenses
 * and purchase requests. Everything here is about the person of the token and nobody else; the role employee reaches
 * only this module's API and its own notification settings (SecurityConfig).
 */
@ApplicationModule(displayName = "Employee cabinet")
package ai.costguard.core.cabinet;

import org.springframework.modulith.ApplicationModule;
