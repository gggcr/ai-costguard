package ai.costguard.core.imports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps uploaded files on local disk under {@code costguard.imports.storage-dir}. The board stores them in S3
 * (SeaweedFS locally); swapping this class for an S3 client keeps the keys, which are {@code tenant/sha256}.
 */
@Component
class FileStore {

    private final Path root;

    FileStore(@Value("${costguard.imports.storage-dir:${java.io.tmpdir}/costguard-imports}") Path root) {
        this.root = root;
    }

    /** Writing the same key twice keeps one copy: the key is the content hash. */
    void put(String key, InputStream content) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Key leaves the storage root");
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "upload", ".part");
            Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean exists(String key) {
        return Files.exists(root.resolve(key));
    }

    private Path inside(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root.normalize()) || target.equals(root.normalize())) {
            throw new IllegalArgumentException("Key leaves the storage root");
        }
        return target;
    }

    /** Deleting a file that is gone already is fine: retention and erasure may run again. */
    boolean delete(String key) {
        try {
            return Files.deleteIfExists(inside(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every file under the tenant's prefix: the uploads, and what the ingest pipeline keeps next to them when it
     * shares this storage (its extraction results and the examples people corrected). Returns how many went.
     */
    int deleteTenant(UUID tenantId) {
        Path dir = inside(tenantId.toString());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int[] count = {0};
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) {
                        count[0]++;
                    }
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return count[0];
    }
}
