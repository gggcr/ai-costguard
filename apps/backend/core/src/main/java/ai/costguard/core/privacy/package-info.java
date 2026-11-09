/**
 * Retention and erasure of personal data (ADR 0021): what each kind of data is kept for, the job that removes what
 * is past its term, erasing an employee on request and erasing a whole company that leaves. Owns ERASURE,
 * COMPANY_ERASURE, PRIVACY_TENANT.
 *
 * <p>Every module with tables takes part through {@link ai.costguard.core.privacy.DataHolder}, so this module
 * depends on none of them; the org module says who a person is through
 * {@link ai.costguard.core.privacy.ErasureSubjects}.
 */
@ApplicationModule(displayName = "Privacy")
package ai.costguard.core.privacy;

import org.springframework.modulith.ApplicationModule;
