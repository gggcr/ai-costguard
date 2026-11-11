package ai.costguard.core.imports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Uploaded files in the S3 bucket the ingest pipeline reads ({@code INGEST_S3_BUCKET}): Yandex Object Storage in the
 * cluster, SeaweedFS locally. Every replica of the core sees the same files, and a pod that restarts loses none.
 */
class S3FileStore implements FileStore {

    private final S3Client s3;
    private final String bucket;

    S3FileStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream content) {
        check(key);
        byte[] bytes;
        try {
            bytes = content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
    }

    @Override
    public boolean exists(String key) {
        check(key);
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public boolean delete(String key) {
        check(key);
        boolean existed = exists(key);
        // deleting a missing object is not an error in S3, so a repeated erasure is fine
        s3.deleteObject(b -> b.bucket(bucket).key(key));
        return existed;
    }

    @Override
    public int deleteTenant(UUID tenantId) {
        String prefix = tenantId + "/";
        int count = 0;
        for (var page : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix))) {
            List<ObjectIdentifier> ids = new ArrayList<>();
            for (S3Object o : page.contents()) {
                ids.add(ObjectIdentifier.builder().key(o.key()).build());
            }
            if (ids.isEmpty()) {
                continue;
            }
            // at most 1000 keys per page, the limit of one DeleteObjects
            s3.deleteObjects(b -> b.bucket(bucket).delete(Delete.builder().objects(ids).quiet(true).build()));
            count += ids.size();
        }
        return count;
    }

    /** The same rule as the local store: a key is {@code tenant/...}, never empty and never climbing out. */
    private static void check(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..") || key.contains("\\")) {
            throw new IllegalArgumentException("Bad storage key");
        }
    }
}
