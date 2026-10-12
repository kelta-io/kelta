package io.kelta.worker.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

/**
 * Presigned access to the platform's <b>media</b> bucket — the object store behind media fields
 * ({@code fieldTypeConfig.media}, see {@link MediaFieldConfig} and
 * {@code MediaUploadController}).
 *
 * <p>Deliberately separate from {@link S3StorageService} (the {@code attachments} bucket). That
 * service's {@code S3ConfigProperties} is registered once, platform-wide, and is injected
 * unqualified by governor limits, {@code FileController} and {@code AttachmentUploadController};
 * a second bean of the same type would need a qualifier at each of those sites. Media needs none
 * of what that service carries (no streaming or range GET, no governor-limit bookkeeping), so it
 * stays a small {@code @Value}-bound service with no shared bean surface. Endpoint, region and
 * credentials default to the attachments store's ({@code application.yml}), so a deployment
 * whose media bucket lives on the same store only names the bucket.
 *
 * <p>This class only ever <em>presigns</em> — a local HMAC computation with no network call. It
 * writes nothing to the database; the record that references an uploaded object is created by
 * the client through the ordinary collection route, which is where Cerbos and owner scoping
 * apply.
 *
 * <p>The bean is always registered (no {@code @Conditional}) so the native image always includes
 * it; {@code kelta.storage.media.enabled} gates presigner construction at runtime.
 *
 * @since 1.0.0
 */
@Service
public class MediaStorageService {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageService.class);

    private final boolean enabled;
    private final String bucket;
    private final long maxFileSize;
    private final Duration expiry;
    private final S3Presigner presigner;

    public MediaStorageService(
            @Value("${kelta.storage.media.enabled:false}") boolean enabled,
            @Value("${kelta.storage.media.endpoint:}") String endpoint,
            @Value("${kelta.storage.media.public-endpoint:}") String publicEndpoint,
            @Value("${kelta.storage.media.region:garage}") String region,
            @Value("${kelta.storage.media.bucket:kelta-media}") String bucket,
            @Value("${kelta.storage.media.access-key:}") String accessKey,
            @Value("${kelta.storage.media.secret-key:}") String secretKey,
            @Value("${kelta.storage.media.max-file-size:15728640}") long maxFileSize,
            @Value("${kelta.storage.media.presigned-url-expiry-minutes:15}") int expiryMinutes) {
        this.bucket = bucket;
        this.maxFileSize = maxFileSize;
        this.expiry = Duration.ofMinutes(expiryMinutes);

        String presignEndpoint = publicEndpoint != null && !publicEndpoint.isBlank() ? publicEndpoint : endpoint;
        if (enabled && (presignEndpoint == null || presignEndpoint.isBlank())) {
            // Fail soft, not at boot: a worker with no media endpoint still serves everything else.
            log.warn("Media storage enabled but no endpoint configured "
                    + "(kelta.storage.media.endpoint / public-endpoint); media uploads are disabled");
            enabled = false;
        }
        this.enabled = enabled;

        if (enabled) {
            this.presigner = S3Presigner.builder()
                    .endpointOverride(URI.create(presignEndpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build())
                    .build();
            log.info("MediaStorageService initialized with bucket '{}'", bucket);
        } else {
            this.presigner = null;
            log.info("MediaStorageService disabled (kelta.storage.media.enabled=false)");
        }
    }

    /** Whether media storage is configured; every presign throws when it is not. */
    public boolean isEnabled() {
        return enabled && presigner != null;
    }

    /** Platform ceiling on one media object, in bytes; a field's own limit can only lower it. */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    public Duration getDefaultExpiry() {
        return expiry;
    }

    /**
     * Presigns a PUT for {@code storageKey}. The client must send exactly {@code contentType}
     * as its {@code Content-Type} header, since it is part of the signature.
     */
    public String getPresignedUploadUrl(String storageKey, String contentType) {
        requireEnabled();
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(putObjectRequest)
                .build();

        return presigner.presignPutObject(presignRequest).url().toString();
    }

    /**
     * Presigns a short-lived GET for {@code storageKey}. Callers authorize first — this method
     * checks nothing about who may read the object.
     */
    public String getPresignedDownloadUrl(String storageKey) {
        requireEnabled();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getObjectRequest)
                .build();

        return presigner.presignGetObject(presignRequest).url().toString();
    }

    @PreDestroy
    public void close() {
        if (presigner != null) {
            presigner.close();
        }
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "Media storage is not enabled (kelta.storage.media.enabled=false)");
        }
    }
}
