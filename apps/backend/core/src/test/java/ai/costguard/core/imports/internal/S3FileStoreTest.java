package ai.costguard.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.core.sync.ResponseTransformer;

/** The store the cluster uses, against SeaweedFS with the keys of infra/local (ADR 0049). */
@Testcontainers
class S3FileStoreTest {

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>("chrislusf/seaweedfs:3.80")
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of(System.getProperty("repo.root"), "infra", "local", "seaweedfs", "s3.json")), "/etc/s3.json")
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/s3.json")
            .withExposedPorts(8333)
            .waitingFor(Wait.forListeningPorts(8333).withStartupTimeout(Duration.ofMinutes(2)));

    static S3FileStore store;
    static software.amazon.awssdk.services.s3.S3Client client;

    @BeforeAll
    static void connect() throws Exception {
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(8333);
        FileStore made = null;
        // SeaweedFS opens the port a moment before its S3 answers
        for (int i = 0; i < 30 && made == null; i++) {
            try {
                made = new FileStoreConfig().fileStore("costguard-imports", Path.of("unused"), endpoint, "ru-central1",
                        "costguard", "costguard-secret", true);
            } catch (RuntimeException e) {
                Thread.sleep(1000);
            }
        }
        assertThat(made).isInstanceOf(S3FileStore.class);
        store = (S3FileStore) made;
        client = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(java.net.URI.create(endpoint)).forcePathStyle(true)
                .region(software.amazon.awssdk.regions.Region.of("ru-central1"))
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create())
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("costguard", "costguard-secret")))
                .build();
    }

    @Test
    void aFileGoesToTheBucketTheIngestPipelineReads() {
        UUID tenant = UUID.randomUUID();
        String key = tenant + "/abc123";
        assertThat(store.exists(key)).isFalse();
        store.put(key, new ByteArrayInputStream("счёт".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists(key)).isTrue();
        byte[] read = client.getObject(b -> b.bucket("costguard-imports").key(key), ResponseTransformer.toBytes())
                .asByteArray();
        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("счёт");
        // the same key twice keeps one copy
        store.put(key, new ByteArrayInputStream("счёт".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.delete(key)).isTrue();
        assertThat(store.exists(key)).isFalse();
        assertThat(store.delete(key)).isFalse(); // erasure may run again
    }

    @Test
    void anErasedTenantLeavesNothingAndOthersKeepTheirs() {
        UUID gone = UUID.randomUUID();
        UUID stays = UUID.randomUUID();
        store.put(gone + "/one", new ByteArrayInputStream(new byte[] {1}));
        store.put(gone + "/examples/two", new ByteArrayInputStream(new byte[] {2})); // what ingest keeps next to it
        store.put(stays + "/three", new ByteArrayInputStream(new byte[] {3}));
        assertThat(store.deleteTenant(gone)).isEqualTo(2);
        assertThat(store.exists(gone + "/one")).isFalse();
        assertThat(store.exists(stays + "/three")).isTrue();
        assertThat(store.deleteTenant(gone)).isZero();
    }

    @Test
    void aKeyCannotClimbOut() {
        assertThatThrownBy(() -> store.put("../x", new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.exists("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutABucketTheFilesStayOnDisk() {
        assertThat(new FileStoreConfig().fileStore("", Path.of("build", "tmp-files"), "", "ru-central1", "", "", false))
                .isInstanceOf(LocalFileStore.class);
    }
}
