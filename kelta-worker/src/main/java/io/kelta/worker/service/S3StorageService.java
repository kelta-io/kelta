package io.kelta.worker.service;

import io.kelta.worker.config.S3ConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Service for S3 file operations: presigned URL generation and direct streaming.
 *
 * <p>Uses a separate public endpoint for presigned URLs (browser access) and
 * an internal endpoint for server-side streaming (FileController).
 *
 * <p>The bean is always registered so that GraalVM native-image AOT processing
 * always includes it. S3 client construction is gated at runtime on
 * {@code kelta.storage.s3.enabled}; when disabled, clients are not built and
 * any operation requiring S3 throws {@link IllegalStateException}. Use
 * {@link #isEnabled()} to check availability before calling.
 *
 * @since 1.0.0
 */
@Service
public class S3StorageService {

    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

    private final S3ConfigProperties config;
    private S3Presigner s3Presigner;
    private S3Client s3Client;

    public S3StorageService(S3ConfigProperties config) {
        this.config = config;
        if (config.isEnabled()) {
            this.s3Presigner = buildPresigner(config);
            this.s3Client = buildS3Client(config);
            logger.info("S3StorageService initialized with bucket '{}', publicEndpoint '{}'",
                    config.getBucket(), config.getPublicEndpoint());
        } else {
            logger.info("S3StorageService disabled (kelta.storage.s3.enabled=false); S3 operations will fail");
        }
    }

    /**
     * Returns whether S3 storage is enabled and ready for use.
     *
     * @return true if S3 clients are initialized
     */
    public boolean isEnabled() {
        return config.isEnabled() && s3Presigner != null && s3Client != null;
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "S3 storage is not enabled (kelta.storage.s3.enabled=false)");
        }
    }

    /**
     * Holds a streamed S3 object with metadata. Must be closed after use.
     *
     * <p>{@code contentRange} is the store's {@code Content-Range} for a ranged read
     * ({@code bytes <first>-<last>/<size>}), null for a whole-object read.
     */
    public record StorageObject(
            InputStream content,
            String contentType,
            long contentLength,
            String fileName,
            String contentRange
    ) implements Closeable {
        public StorageObject(InputStream content, String contentType, long contentLength, String fileName) {
            this(content, contentType, contentLength, fileName, null);
        }

        @Override
        public void close() throws IOException {
            if (content != null) {
                content.close();
            }
        }
    }

    /**
     * Generates a presigned download URL for the given S3 object key.
     *
     * @param storageKey the S3 object key (e.g., "tenant-1/col-1/rec-1/uuid/file.pdf")
     * @param expiry the URL expiry duration
     * @return the presigned download URL
     */
    public String getPresignedDownloadUrl(String storageKey, Duration expiry) {
        requireEnabled();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(config.getBucket())
                .key(storageKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    /**
     * Generates a presigned upload URL for direct-to-S3 PUT uploads.
     *
     * @param storageKey  the S3 object key (e.g., "tenant-1/col-1/rec-1/uuid/file.pdf")
     * @param contentType the expected Content-Type of the upload
     * @param expiry      the URL expiry duration
     * @return the presigned upload URL
     */
    public String getPresignedUploadUrl(String storageKey, String contentType, Duration expiry) {
        requireEnabled();
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(config.getBucket())
                .key(storageKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        return presignedRequest.url().toString();
    }

    /**
     * Returns the configured maximum file upload size in bytes.
     *
     * @return the max file size
     */
    public long getMaxFileSize() {
        return config.getMaxFileSize();
    }

    /**
     * Returns the configured presigned URL expiry duration.
     *
     * @return the expiry duration
     */
    public Duration getDefaultExpiry() {
        return Duration.ofMinutes(config.getPresignedUrlExpiryMinutes());
    }

    /**
     * Streams file content from S3 for direct serving.
     *
     * @param storageKey the S3 object key
     * @return a StorageObject containing the stream, content type, size, and filename
     * @throws NoSuchKeyException if the key does not exist
     */
    public StorageObject streamObject(String storageKey) {
        requireEnabled();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(config.getBucket())
                .key(storageKey)
                .build();

        ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
        GetObjectResponse metadata = response.response();

        String contentType = metadata.contentType() != null
                ? metadata.contentType()
                : "application/octet-stream";
        long contentLength = metadata.contentLength() != null
                ? metadata.contentLength()
                : -1;
        String fileName = extractFileName(storageKey);

        return new StorageObject(response, contentType, contentLength, fileName);
    }

    /**
     * Streams a byte range from an S3 object for partial content (206) responses.
     *
     * @param storageKey the S3 object key
     * @param rangeHeader the HTTP Range header value (e.g., "bytes=0-1023")
     * @return a StorageObject containing the partial stream
     */
    public StorageObject streamObjectRange(String storageKey, String rangeHeader) {
        requireEnabled();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(config.getBucket())
                .key(storageKey)
                .range(rangeHeader)
                .build();

        ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
        GetObjectResponse metadata = response.response();

        String contentType = metadata.contentType() != null
                ? metadata.contentType()
                : "application/octet-stream";
        long contentLength = metadata.contentLength() != null
                ? metadata.contentLength()
                : -1;

        return new StorageObject(response, contentType, contentLength, extractFileName(storageKey),
                metadata.contentRange());
    }

    /**
     * Uploads bytes directly to S3.
     *
     * @param storageKey  the S3 object key
     * @param data        the bytes to upload
     * @param contentType the content type (e.g., "application/java-archive")
     */
    public void uploadObject(String storageKey, byte[] data, String contentType) {
        requireEnabled();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(config.getBucket())
                .key(storageKey)
                .contentType(contentType)
                .contentLength((long) data.length)
                .build();

        s3Client.putObject(request,
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(data));
        logger.info("Uploaded {} bytes to s3://{}/{}", data.length, config.getBucket(), storageKey);
    }

    /**
     * Deletes an S3 object.
     *
     * @param storageKey the S3 object key
     */
    public void deleteObject(String storageKey) {
        requireEnabled();
        s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(config.getBucket())
                .key(storageKey)
                .build());
        logger.info("Deleted s3://{}/{}", config.getBucket(), storageKey);
    }

    /**
     * Checks if an S3 object exists without downloading it.
     */
    public boolean objectExists(String storageKey) {
        requireEnabled();
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(config.getBucket())
                    .key(storageKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private String extractFileName(String storageKey) {
        int lastSlash = storageKey.lastIndexOf('/');
        return lastSlash >= 0 ? storageKey.substring(lastSlash + 1) : storageKey;
    }

    @PreDestroy
    public void close() {
        if (s3Presigner != null) {
            s3Presigner.close();
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    private static S3Presigner buildPresigner(S3ConfigProperties config) {
        String endpoint = config.getPublicEndpoint() != null
                ? config.getPublicEndpoint()
                : config.getEndpoint();

        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(config.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static S3Client buildS3Client(S3ConfigProperties config) {
        // Use internal endpoint for server-to-server operations
        return S3Client.builder()
                .endpointOverride(URI.create(config.getEndpoint()))
                .region(Region.of(config.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
                .forcePathStyle(true)
                // Required for S3-COMPATIBLE stores; this deployment runs Garage, not AWS.
                //
                // SDK 2.30 changed the default to WHEN_SUPPORTED, which adds a flexible-checksum
                // trailer and sends the literal header
                //   x-amz-content-sha256: STREAMING-UNSIGNED-PAYLOAD-TRAILER
                // instead of a hex digest. Garage parses that as a hash and rejects the request:
                //   "Invalid content sha256 hash: Invalid character 'S' at position 0"
                // — the 'S' being the start of STREAMING.
                //
                // WHEN_REQUIRED still sends a checksum wherever the S3 API mandates one, so
                // integrity is not being traded away; it only stops the SDK adding trailers the
                // server cannot parse.
                //
                // This affects every server-side upload — module JARs, data exports and
                // telehealth archives. It went unnoticed because ordinary attachments upload
                // via presigned URLs straight from the browser, which never sends the trailer.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                // Both settings are needed, and this is the one that actually removes the
                // STREAMING-UNSIGNED-PAYLOAD-TRAILER header: WHEN_REQUIRED alone stops the
                // flexible-checksum trailer but leaves aws-chunked framing on, so the sentinel
                // value is still sent and Garage still rejects it. Verified by
                // S3StorageServiceChecksumTest against a real client on a loopback socket.
                .serviceConfiguration(S3Configuration.builder()
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    // Package-private setters for testing
    void setS3Presigner(S3Presigner s3Presigner) {
        this.s3Presigner = s3Presigner;
    }

    void setS3Client(S3Client s3Client) {
        this.s3Client = s3Client;
    }
}
