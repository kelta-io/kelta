package io.kelta.worker.service;

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
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

/**
 * Presigned-upload access to spotopened's own {@code spotopened-media} Garage
 * bucket -- deliberately NOT {@link S3StorageService} pointed at a different
 * bucket. That service's {@code S3ConfigProperties} bean is registered once,
 * platform-wide, via {@code @EnableConfigurationProperties} in
 * {@code WorkerApplication}; introducing a second bean of the same type for
 * one tenant's bucket would need a qualifier at every existing injection
 * site (governor limits, {@code FileController}, {@code
 * AttachmentUploadController}) to stay unambiguous, for a service this class
 * doesn't need any of (no streaming/range GET, no governor-limit bookkeeping
 * -- this bucket isn't the shared multi-tenant one those limits govern).
 * {@code @Value} injection here means zero shared bean surface to reason
 * about.
 *
 * <p>Read-only in the sense that matters: this class only ever mints PUT
 * URLs. The metadata row (facilityId, storageKey, caption, ...) is written
 * through the ordinary generic route ({@code POST /api/facility-photos}),
 * which is already Cerbos-gated and owner-guarded by the collection's
 * ownership metadata ({@code OwnerScopeGuardHook}) --
 * there is no separate "finalize" step or pending-row bookkeeping the way
 * {@link AttachmentUploadController} needs, because nothing here writes to
 * a database at all.
 *
 * @since 1.0.0
 */
@Service
public class SpotopenedMediaStorageService {

    private static final Logger log = LoggerFactory.getLogger(SpotopenedMediaStorageService.class);

    private final boolean enabled;
    private final String bucket;
    private final long maxFileSize;
    private final Duration expiry;
    private final S3Presigner presigner;

    public SpotopenedMediaStorageService(
            @Value("${kelta.storage.spotopened-media.enabled:false}") boolean enabled,
            @Value("${kelta.storage.spotopened-media.endpoint:}") String endpoint,
            @Value("${kelta.storage.spotopened-media.public-endpoint:}") String publicEndpoint,
            @Value("${kelta.storage.spotopened-media.region:garage}") String region,
            @Value("${kelta.storage.spotopened-media.bucket:spotopened-media}") String bucket,
            @Value("${kelta.storage.spotopened-media.access-key:}") String accessKey,
            @Value("${kelta.storage.spotopened-media.secret-key:}") String secretKey,
            @Value("${kelta.storage.spotopened-media.max-file-size:15728640}") long maxFileSize,
            @Value("${kelta.storage.spotopened-media.presigned-url-expiry-minutes:15}") int expiryMinutes) {
        this.enabled = enabled;
        this.bucket = bucket;
        this.maxFileSize = maxFileSize;
        this.expiry = Duration.ofMinutes(expiryMinutes);

        if (enabled) {
            String presignEndpoint = !publicEndpoint.isBlank() ? publicEndpoint : endpoint;
            this.presigner = S3Presigner.builder()
                    .endpointOverride(URI.create(presignEndpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build())
                    .build();
            log.info("SpotopenedMediaStorageService initialized with bucket '{}'", bucket);
        } else {
            this.presigner = null;
            log.info("SpotopenedMediaStorageService disabled "
                    + "(kelta.storage.spotopened-media.enabled=false)");
        }
    }

    public boolean isEnabled() {
        return enabled && presigner != null;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public Duration getDefaultExpiry() {
        return expiry;
    }

    public String getPresignedUploadUrl(String storageKey, String contentType) {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "spotopened-media storage is not enabled (kelta.storage.spotopened-media.enabled=false)");
        }
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    /** Public-read stand-in: this bucket is not in Garage's website/public-access
     *  mode (that needs an [s3_web] listener + a shared-Garage-instance restart --
     *  see the ops discussion this was deferred from), so a short-lived presigned
     *  GET is what a plain {@code <img src>} actually loads. No content-existence
     *  or ownership check here on purpose: every facility-photos row this key
     *  could belong to is meant to be publicly readable already (that is the
     *  entire point of the Guest-read grant on facility-photos), so there is no
     *  confidentiality boundary a presigned URL could cross that the collection's
     *  own Cerbos grant doesn't already cross. */
    public String getPresignedDownloadUrl(String storageKey) {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "spotopened-media storage is not enabled (kelta.storage.spotopened-media.enabled=false)");
        }
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
        return presigned.url().toString();
    }
}
