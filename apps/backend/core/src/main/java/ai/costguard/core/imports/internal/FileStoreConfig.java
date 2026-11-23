package ai.costguard.core.imports.internal;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 when {@code costguard.imports.s3-bucket} is set (the Helm chart always sets it, ADR 0049), the local folder
 * otherwise. The endpoint, region and keys are the ones every service reads: {@code S3_ENDPOINT}, {@code S3_REGION},
 * {@code S3_ACCESS_KEY}, {@code S3_SECRET_KEY}.
 */
@Configuration(proxyBeanMethods = false)
class FileStoreConfig {

    @Bean
    FileStore fileStore(@Value("${costguard.imports.s3-bucket:}") String bucket,
            @Value("${costguard.imports.storage-dir:${java.io.tmpdir}/costguard-imports}") Path dir,
            @Value("${S3_ENDPOINT:}") String endpoint,
            @Value("${S3_REGION:ru-central1}") String region,
            @Value("${S3_ACCESS_KEY:}") String accessKey,
            @Value("${S3_SECRET_KEY:}") String secretKey,
            @Value("${costguard.imports.s3-create-bucket:false}") boolean createBucket) {
        if (bucket.isBlank()) {
            return new LocalFileStore(dir);
        }
        var builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(region))
                // Yandex Object Storage and SeaweedFS: path-style addresses, checksums only where S3 requires them
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                // No key: the default chain, i.e. the IAM role of the pod's service account on EKS (deploy/terraform/aws)
                .credentialsProvider(accessKey.isBlank()
                        ? software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder().build()
                        : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        S3Client s3 = builder.build();
        if (createBucket) {
            // a laptop only: in the cloud Terraform makes the bucket, and the key may not create one
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
            } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                s3.createBucket(b -> b.bucket(bucket));
            }
        }
        return new S3FileStore(s3, bucket);
    }
}
