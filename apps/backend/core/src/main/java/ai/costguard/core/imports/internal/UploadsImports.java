package ai.costguard.core.imports.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Uploading a file and confirming its mapping: the people who edit imports, and the service account of a company's
 * job that brings FOCUS exports from its bucket (role importer, ADR 0046). Values the pipeline was unsure about are
 * still resolved by people only.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('owner', 'finance', 'it_admin', 'importer')")
@interface UploadsImports {
}
