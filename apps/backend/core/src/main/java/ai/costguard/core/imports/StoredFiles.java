package ai.costguard.core.imports;

import java.util.Optional;

/**
 * The company's file storage (S3 in a cluster, ADR 0049) for other modules: employee documents keep their files
 * here (ADR 0052). Keys start with the tenant id, so erasing a company removes them with its uploads.
 */
public interface StoredFiles {

    void put(String key, byte[] content);

    Optional<byte[]> get(String key);

    boolean delete(String key);
}
