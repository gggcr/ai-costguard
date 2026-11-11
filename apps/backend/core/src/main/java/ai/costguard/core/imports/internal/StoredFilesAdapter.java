package ai.costguard.core.imports.internal;

import ai.costguard.core.imports.StoredFiles;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class StoredFilesAdapter implements StoredFiles {

    private final FileStore files;

    StoredFilesAdapter(FileStore files) {
        this.files = files;
    }

    @Override
    public void put(String key, byte[] content) {
        files.put(key, new ByteArrayInputStream(content));
    }

    @Override
    public Optional<byte[]> get(String key) {
        return files.get(key);
    }

    @Override
    public boolean delete(String key) {
        return files.delete(key);
    }
}
