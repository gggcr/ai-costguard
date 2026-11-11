package ai.costguard.core.imports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

/**
 * Uploaded files on local disk under {@code costguard.imports.storage-dir}: for a laptop and tests only, when no
 * bucket is set. Two replicas of the core, or the core and the ingest pipeline in different pods, do not share a
 * disk; the cluster uses {@link S3FileStore}.
 */
class LocalFileStore implements FileStore {

    private final Path root;

    LocalFileStore(Path root) {
        this.root = root;
    }

    @Override
    public void put(String key, InputStream content) {
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

    @Override
    public java.util.Optional<byte[]> get(String key) {
        Path path = inside(key);
        if (!Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(root.resolve(key));
    }

    private Path inside(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root.normalize()) || target.equals(root.normalize())) {
            throw new IllegalArgumentException("Key leaves the storage root");
        }
        return target;
    }

    @Override
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(inside(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int deleteTenant(UUID tenantId) {
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
