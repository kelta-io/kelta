package io.kelta.gateway.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GeoIpDatabaseManager Tests")
class GeoIpDatabaseManagerTest {

    static final long BUILD_EPOCH = 1_784_000_000L;

    /** Manager with downloads disabled (no license key), so refresh() only loads from disk. */
    static GeoIpDatabaseManager localOnlyManager(Path dbPath) {
        return new GeoIpDatabaseManager(true, dbPath.toString(), "", "http://unused?suffix=tar.gz", 7, 1000);
    }

    // ---- loading from disk ----

    @Test
    @DisplayName("loads an on-disk database into a reader whose metadata decodes on 4.x")
    void loadsDatabaseFromDisk(@TempDir Path dir) throws Exception {
        Path db = TestMmdbWriter.write(dir.resolve("GeoLite2-City.mmdb"), "203.0.113.0", 24,
                TestMmdbWriter.cityRecord(), BUILD_EPOCH);
        GeoIpDatabaseManager manager = localOnlyManager(db);

        manager.refresh();

        assertThat(manager.getReader()).isNotNull();
        assertThat(manager.getReader().getMetadata().buildTime()).isEqualTo(Instant.ofEpochSecond(BUILD_EPOCH));
        assertThat(manager.getReader().getMetadata().databaseType()).isEqualTo("GeoLite2-City");
    }

    @Test
    @DisplayName("fails open with no reader when the database file is missing")
    void failsOpenWhenDatabaseFileMissing(@TempDir Path dir) {
        GeoIpDatabaseManager manager = localOnlyManager(dir.resolve("absent/GeoLite2-City.mmdb"));

        manager.refresh();

        assertThat(manager.getReader()).isNull();
    }

    @Test
    @DisplayName("fails open with no reader when the database file is corrupt")
    void failsOpenWhenDatabaseFileCorrupt(@TempDir Path dir) throws Exception {
        Path db = Files.writeString(dir.resolve("GeoLite2-City.mmdb"), "not an mmdb");
        GeoIpDatabaseManager manager = localOnlyManager(db);

        manager.refresh();

        assertThat(manager.getReader()).isNull();
    }

    // ---- tar.gz extraction ----

    @Test
    @DisplayName("extracts the .mmdb entry from a GeoLite2-style tar.gz")
    void extractsMmdbEntry() throws Exception {
        byte[] mmdb = "fake-mmdb-content".getBytes(StandardCharsets.US_ASCII);
        byte[] archive = tarGz(
                entry("GeoLite2-City_20260716/COPYRIGHT.txt", "copyright".getBytes(StandardCharsets.US_ASCII)),
                entry("GeoLite2-City_20260716/GeoLite2-City.mmdb", mmdb));

        byte[] extracted = GeoIpDatabaseManager.extractMmdb(new ByteArrayInputStream(archive));

        assertThat(extracted).isEqualTo(mmdb);
    }

    @Test
    @DisplayName("throws when the archive has no .mmdb entry")
    void throwsWhenNoMmdbEntry() throws Exception {
        byte[] archive = tarGz(entry("readme.txt", "hello".getBytes(StandardCharsets.US_ASCII)));

        assertThatThrownBy(() -> GeoIpDatabaseManager.extractMmdb(new ByteArrayInputStream(archive)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no .mmdb entry");
    }

    // ---- sha256 ----

    @Test
    @DisplayName("computes the sha256 of a file")
    void computesSha256(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.bin");
        Files.writeString(file, "abc");
        // Well-known SHA-256 of "abc"
        assertThat(GeoIpDatabaseManager.sha256Hex(file))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    // ---- license-key redaction ----

    @Test
    @DisplayName("redacts the license key from log-bound strings")
    void redactsLicenseKey() {
        String msg = "GET https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-City"
                + "&suffix=tar.gz&license_key=SECRET123 failed";
        assertThat(GeoIpDatabaseManager.redact(msg)).doesNotContain("SECRET123")
                .contains("license_key=***");
        assertThat(GeoIpDatabaseManager.redact(null)).isNull();
    }

    // ---- tar test helpers (ustar layout: name@0, size octal@124, data in 512-byte blocks) ----

    private record TarEntry(String name, byte[] data) {
    }

    private static TarEntry entry(String name, byte[] data) {
        return new TarEntry(name, data);
    }

    private static byte[] tarGz(TarEntry... entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (TarEntry e : entries) {
            byte[] header = new byte[512];
            byte[] name = e.name().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, header, 0, name.length);
            byte[] size = String.format("%011o", e.data().length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(size, 0, header, 124, size.length);
            tar.write(header);
            tar.write(e.data());
            int pad = (512 - (e.data().length % 512)) % 512;
            tar.write(new byte[pad]);
        }
        tar.write(new byte[1024]); // end-of-archive marker

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(tar.toByteArray());
        }
        return out.toByteArray();
    }
}
