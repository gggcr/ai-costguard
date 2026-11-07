package ai.costguard.core.imports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
}
