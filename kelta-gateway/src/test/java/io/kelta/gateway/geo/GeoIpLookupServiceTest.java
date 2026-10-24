package io.kelta.gateway.geo;

import com.maxmind.db.Reader;
import io.kelta.gateway.geo.model.GeoCity;
import io.kelta.gateway.geo.model.GeoCityData;
import io.kelta.gateway.geo.model.GeoCountry;
import io.kelta.gateway.geo.model.GeoLocation;
import io.kelta.gateway.geo.model.GeoSubdivision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GeoIpLookupService Tests")
class GeoIpLookupServiceTest {

    private final GeoIpDatabaseManager manager = mock(GeoIpDatabaseManager.class);
    private final GeoIpLookupService service = new GeoIpLookupService(manager);

    @Test
    @DisplayName("fails open when no reader is loaded")
    void failsOpenWithoutReader() {
        when(manager.getReader()).thenReturn(null);
        assertThat(service.lookup("203.0.113.50")).isEmpty();
    }

    @Test
    @DisplayName("skips private, loopback, CGNAT and ULA addresses without touching the reader")
    void skipsNonPublicAddresses() throws Exception {
        Reader reader = mock(Reader.class);
        when(manager.getReader()).thenReturn(reader);

        for (String ip : List.of("127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.0.1",
                "169.254.1.1", "100.64.0.1", "100.127.255.254", "::1", "fc00::1", "fdab::1")) {
            assertThat(service.lookup(ip)).as("ip %s should be skipped", ip).isEmpty();
        }
    }

    @Test
    @DisplayName("treats public addresses as lookupable")
    void publicAddressesAreLookupable() throws Exception {
        assertThat(GeoIpLookupService.isNonPublic(InetAddress.ofLiteral("203.0.113.50"))).isFalse();
        assertThat(GeoIpLookupService.isNonPublic(InetAddress.ofLiteral("100.128.0.1"))).isFalse();
        assertThat(GeoIpLookupService.isNonPublic(InetAddress.ofLiteral("2001:db8::1"))).isFalse();
    }

    @Test
    @DisplayName("maps a database hit to a GeoResult")
    void mapsDatabaseHit() throws Exception {
        Reader reader = mock(Reader.class);
        when(manager.getReader()).thenReturn(reader);
        GeoCityData data = new GeoCityData(
                new GeoCountry("PT"),
                List.of(new GeoSubdivision("11", Map.of("en", "Lisbon"))),
                new GeoCity(Map.of("en", "Cascais")),
                new GeoLocation(38.6979, -9.4207, 20));
        when(reader.get(any(InetAddress.class), eq(GeoCityData.class))).thenReturn(data);

        Optional<GeoResult> result = service.lookup("203.0.113.50");

        assertThat(result).isPresent();
        assertThat(result.get().country()).isEqualTo("PT");
        assertThat(result.get().region()).isEqualTo("Lisbon");
        assertThat(result.get().city()).isEqualTo("Cascais");
        assertThat(result.get().latitude()).isEqualTo(38.6979);
        assertThat(result.get().longitude()).isEqualTo(-9.4207);
        assertThat(result.get().accuracyKm()).isEqualTo(20);
    }

    @Test
    @DisplayName("returns empty for a miss or a record without a country")
    void returnsEmptyForMissOrNoCountry() throws Exception {
        Reader reader = mock(Reader.class);
        when(manager.getReader()).thenReturn(reader);
        when(reader.get(any(InetAddress.class), eq(GeoCityData.class))).thenReturn(null);
        assertThat(service.lookup("203.0.113.50")).isEmpty();

        when(reader.get(any(InetAddress.class), eq(GeoCityData.class)))
                .thenReturn(new GeoCityData(null, null, null, null));
        assertThat(service.lookup("203.0.113.50")).isEmpty();
    }

    @Test
    @DisplayName("fails open on unparseable input and reader errors")
    void failsOpenOnErrors() throws Exception {
        Reader reader = mock(Reader.class);
        when(manager.getReader()).thenReturn(reader);
        assertThat(service.lookup("not-an-ip")).isEmpty();
        assertThat(service.lookup(null)).isEmpty();

        when(reader.get(any(InetAddress.class), eq(GeoCityData.class)))
                .thenThrow(new IllegalStateException("closed during hot-swap"));
        assertThat(service.lookup("203.0.113.50")).isEmpty();
    }

    // ---- real MMDB decoded by com.maxmind.db.Reader (no mocked reader) ----

    @Test
    @DisplayName("decodes a database hit into every GeoResult field")
    void decodesRealDatabaseHit(@TempDir Path dir) throws Exception {
        GeoIpLookupService realService = serviceOver(TestMmdbWriter.write(dir.resolve("city.mmdb"),
                "203.0.113.0", 24, TestMmdbWriter.cityRecord(), GeoIpDatabaseManagerTest.BUILD_EPOCH));

        assertThat(realService.lookup("203.0.113.50"))
                .contains(new GeoResult("PT", "Lisbon", "Cascais", 38.6979, -9.4207, 20));
    }

    @Test
    @DisplayName("returns empty for an IP not in the database")
    void returnsEmptyForRealDatabaseMiss(@TempDir Path dir) throws Exception {
        GeoIpLookupService realService = serviceOver(TestMmdbWriter.write(dir.resolve("city.mmdb"),
                "203.0.113.0", 24, TestMmdbWriter.cityRecord(), GeoIpDatabaseManagerTest.BUILD_EPOCH));

        assertThat(realService.lookup("198.51.100.7")).isEmpty();
        assertThat(realService.lookup("203.0.114.1")).isEmpty();
    }

    @Test
    @DisplayName("fails open (no geo result, so no X-Geo-* headers) when the database file is missing")
    void failsOpenWhenDatabaseFileMissing(@TempDir Path dir) {
        GeoIpLookupService realService = serviceOver(dir.resolve("missing.mmdb"));

        assertThat(realService.lookup("203.0.113.50")).isEmpty();
    }

    private static GeoIpLookupService serviceOver(Path dbPath) {
        GeoIpDatabaseManager realManager = GeoIpDatabaseManagerTest.localOnlyManager(dbPath);
        realManager.refresh();
        return new GeoIpLookupService(realManager);
    }
}
