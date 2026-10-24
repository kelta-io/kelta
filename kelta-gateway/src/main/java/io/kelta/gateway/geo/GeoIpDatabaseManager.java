package io.kelta.gateway.geo;

import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * Owns the local GeoLite2-City MMDB file: initial load, periodic re-download, and
 * atomic hot-swap of the in-memory {@link Reader}.
 *
 * <p>The database is per-pod local infrastructure (not tenant configuration), so there is
 * deliberately no NATS broadcast here — every pod refreshes its own copy on its own
 * schedule. Downloads happen only on the scheduler thread, never on a request path.
 *
 * <p><b>Fail-open:</b> if the file is missing, the download fails, or no license key is
 * configured, {@link #getReader()} returns null and geo enrichment is silently skipped.
 *
 * <p><b>The MaxMind license key must never appear in logs</b> — every message that could
 * contain the download URL goes through {@link #redact(String)}.
 */
@Component
public class GeoIpDatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(GeoIpDatabaseManager.class);

    /** Close-grace for the previous reader so in-flight lookups finish before close. */
    private static final Duration OLD_READER_CLOSE_GRACE = Duration.ofSeconds(30);

    private final AtomicReference<Reader> reader = new AtomicReference<>();
    private final AtomicBoolean refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean missingKeyLogged = new AtomicBoolean();

    private final boolean enabled;
    private final Path dbPath;
    private final String licenseKey;
    private final String downloadUrl;
    private final long staleAfterDays;
    private final Duration downloadTimeout;
    private final HttpClient httpClient;

    public GeoIpDatabaseManager(
            @Value("${kelta.gateway.geo.enabled:true}") boolean enabled,
            @Value("${kelta.gateway.geo.db-path:/data/geoip/GeoLite2-City.mmdb}") String dbPath,
            @Value("${kelta.gateway.geo.license-key:}") String licenseKey,
            @Value("${kelta.gateway.geo.download-url:https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-City&suffix=tar.gz}") String downloadUrl,
            @Value("${kelta.gateway.geo.stale-after-days:7}") long staleAfterDays,
            @Value("${kelta.gateway.geo.download-timeout-ms:120000}") long downloadTimeoutMs) {
        this.enabled = enabled;
        this.dbPath = Path.of(dbPath);
        this.licenseKey = licenseKey;
        this.downloadUrl = downloadUrl;
        this.staleAfterDays = staleAfterDays;
        this.downloadTimeout = Duration.ofMillis(downloadTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // MaxMind 302s to object storage
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Current reader, or null when no database is loaded (fail-open). */
    public Reader getReader() {
        return reader.get();
    }

    /**
     * Loads the on-disk database if present, then re-downloads when the file is missing
     * or older than {@code stale-after-days}. First execution fires at startup (no
     * initial delay), which doubles as the boot-time load.
     */
    @Scheduled(fixedDelayString = "${kelta.gateway.geo.refresh-ms:86400000}")
    public void refresh() {
        if (!enabled || !refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            loadFromDiskIfNeeded();
            if (licenseKey == null || licenseKey.isBlank()) {
                if (missingKeyLogged.compareAndSet(false, true)) {
                    log.info("GeoIP: no MaxMind license key configured — automatic database "
                            + "downloads disabled ({})", reader.get() == null
                            ? "no local database; geo enrichment inactive"
                            : "serving existing local database");
                }
                return;
            }
            if (reader.get() != null && !isStale()) {
                return;
            }
            downloadAndSwap();
        } catch (Exception e) {
            // Fail-open: requests proceed without geo headers.
            log.warn("GeoIP database refresh failed: {}", redact(e.toString()));
        } finally {
            refreshInFlight.set(false);
        }
    }

    private void loadFromDiskIfNeeded() {
        if (reader.get() != null || !Files.isRegularFile(dbPath)) {
            return;
        }
        try {
            swap(new Reader(dbPath.toFile(), Reader.FileMode.MEMORY, new CHMCache()));
            log.info("GeoIP: loaded database from {} (built {})", dbPath,
                    reader.get().getMetadata().buildTime());
        } catch (IOException e) {
            log.warn("GeoIP: failed to load existing database at {}: {}", dbPath, e.toString());
        }
    }

    private boolean isStale() {
        try {
            Instant mtime = Files.getLastModifiedTime(dbPath).toInstant();
            return mtime.isBefore(Instant.now().minus(Duration.ofDays(staleAfterDays)));
        } catch (IOException e) {
            return true;
        }
    }

    private void downloadAndSwap() throws Exception {
        Path dir = dbPath.getParent();
        Files.createDirectories(dir);

        Path tarball = Files.createTempFile(dir, "geoip-", ".tar.gz.tmp");
        try {
            String url = downloadUrl + "&license_key=" + licenseKey;
            HttpResponse<Path> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(downloadTimeout).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(tarball));
            if (resp.statusCode() != 200) {
                throw new IOException("download returned HTTP " + resp.statusCode());
            }

            verifySha256(tarball);

            Path mmdbTmp = Files.createTempFile(dir, "geoip-", ".mmdb.tmp");
            try (InputStream in = Files.newInputStream(tarball)) {
                Files.write(mmdbTmp, extractMmdb(in));
            }
            Reader candidate = new Reader(mmdbTmp.toFile(), Reader.FileMode.MEMORY, new CHMCache());
            Files.move(mmdbTmp, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            swap(candidate);
            log.info("GeoIP: database refreshed (built {})", candidate.getMetadata().buildTime());
        } finally {
            Files.deleteIfExists(tarball);
        }
    }

    private void verifySha256(Path tarball) throws Exception {
        String shaUrl = (downloadUrl + "&license_key=" + licenseKey)
                .replace("suffix=tar.gz", "suffix=tar.gz.sha256");
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder(URI.create(shaUrl)).timeout(downloadTimeout).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("sha256 download returned HTTP " + resp.statusCode());
        }
        String expected = resp.body().trim().split("\\s+")[0];
        String actual = sha256Hex(tarball);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("sha256 mismatch: expected " + expected + " got " + actual);
        }
    }

    static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Minimal tar.gz scanner: finds the single {@code *.mmdb} entry and returns its bytes.
     * Hand-rolled (tar headers are 512-byte blocks; name at offset 0/prefix at 345, octal
     * size at 124) to avoid pulling commons-compress into the native image.
     */
    static byte[] extractMmdb(InputStream gzipped) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(gzipped)) {
            byte[] header = new byte[512];
            while (readFully(gz, header)) {
                if (isAllZero(header)) {
                    break; // end-of-archive marker
                }
                String name = tarString(header, 345, 155);
                String base = tarString(header, 0, 100);
                name = name.isEmpty() ? base : name + "/" + base;
                long size = Long.parseLong(tarString(header, 124, 12).trim(), 8);
                long padded = ((size + 511) / 512) * 512;
                if (name.endsWith(".mmdb")) {
                    byte[] data = gz.readNBytes((int) size);
                    if (data.length != size) {
                        throw new IOException("truncated tar entry " + name);
                    }
                    return data;
                }
                skipFully(gz, padded);
            }
        }
        throw new IOException("no .mmdb entry found in archive");
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int read = in.readNBytes(buf, 0, buf.length);
        if (read == 0) {
            return false;
        }
        if (read < buf.length) {
            throw new IOException("truncated tar header");
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        in.skipNBytes(n); // throws EOFException on a truncated archive
    }

    private static boolean isAllZero(byte[] buf) {
        for (byte b : buf) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private void swap(Reader next) {
        Reader old = reader.getAndSet(next);
        if (old != null) {
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(OLD_READER_CLOSE_GRACE);
                    old.close();
                } catch (Exception ignored) {
                    // best-effort close of the retired reader
                }
            });
        }
    }

    /** Strips the MaxMind license key from any string destined for a log. */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("license_key=[^&\\s\"]*", "license_key=***");
    }
}
