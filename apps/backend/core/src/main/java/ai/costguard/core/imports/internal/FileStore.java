package ai.costguard.core.imports.internal;

import java.io.InputStream;
import java.util.UUID;

/**
 * Where uploaded files are kept, by key {@code tenant/sha256}. The ingest pipeline reads them by the same key from the
 * same place (ADR 0049): S3 in a cluster ({@link S3FileStore}), a local folder on a laptop ({@link LocalFileStore}).
 */
interface FileStore {

    /** Writing the same key twice keeps one copy: the key is the content hash. */
    void put(String key, InputStream content);

    boolean exists(String key);

    /** Deleting a file that is gone already is fine: retention and erasure may run again. */
    boolean delete(String key);

    /**
     * Every file under the tenant's prefix: the uploads, and what the ingest pipeline keeps next to them (its
     * extraction results and the examples people corrected). Returns how many went.
     */
    int deleteTenant(UUID tenantId);
}
