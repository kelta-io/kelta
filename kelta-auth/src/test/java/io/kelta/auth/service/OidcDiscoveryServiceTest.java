package io.kelta.auth.service;

import com.sun.net.httpserver.HttpServer;
import io.kelta.auth.service.OidcDiscoveryService.DiscoveryMetadata;
import io.kelta.auth.service.OidcDiscoveryService.EndpointOverrides;
import io.kelta.auth.service.OidcDiscoveryService.ResolvedEndpoints;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OidcDiscoveryService Tests")
class OidcDiscoveryServiceTest {

    private OidcDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new OidcDiscoveryService(new OutboundUrlGuard(""));
    }

    /**
     * Discovery against a real HTTP server on loopback. The guard allows {@code 127.0.0.0/8}
     * except where a test shows the default refuses it.
     */
    @Nested
    @DisplayName("fetch")
    class Fetch {
        private HttpServer server;
        private String issuer;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile int status = 200;
        private volatile String body;
        private volatile String location;
        private volatile long delayMillis;
        private OidcDiscoveryService allowLoopback;

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (location != null) {
                    exchange.getResponseHeaders().add("Location", location);
                }
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
                exchange.close();
            });
            server.start();
            issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/acme";
            body = """
                    {"issuer":"%1$s","authorization_endpoint":"%1$s/auth","token_endpoint":"%1$s/token",
                     "userinfo_endpoint":"%1$s/userinfo","jwks_uri":"%1$s/certs","end_session_endpoint":"%1$s/logout"}
                    """.formatted(issuer);
            allowLoopback = new OidcDiscoveryService(new OutboundUrlGuard("127.0.0.0/8"));
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        @Test
        @DisplayName("an allow-listed private issuer is discovered")
        void allowListedDiscovered() {
            Optional<DiscoveryMetadata> metadata = allowLoopback.discover(issuer);

            assertThat(metadata).isPresent();
            assertThat(metadata.get().tokenEndpoint()).isEqualTo(issuer + "/token");
            assertThat(metadata.get().jwksUri()).isEqualTo(issuer + "/certs");
        }

        @Test
        @DisplayName("with the default guard, a loopback issuer is refused and never fetched")
        void loopbackRefusedByDefault() {
            assertThat(service.discover(issuer)).isEmpty();
            assertThat(requests.get()).isZero();
        }

        @Test
        @DisplayName("a link-local metadata issuer is refused even when loopback is allowed")
        void metadataAddressRefused() {
            assertThat(allowLoopback.discover("http://169.254.169.254/latest")).isEmpty();
        }

        @Test
        @DisplayName("a redirect is not followed")
        void redirectNotFollowed() {
            status = 302;
            location = "http://169.254.169.254/latest/meta-data/";

            assertThat(allowLoopback.discover(issuer)).isEmpty();
            assertThat(requests.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("an oversized document is rejected")
        void oversizedRejected() {
            body = "{\"issuer\":\"" + issuer + "\",\"pad\":\""
                    + "x".repeat(OidcDiscoveryService.MAX_DOCUMENT_BYTES) + "\"}";

            assertThat(allowLoopback.discover(issuer)).isEmpty();
        }

        @Test
        @DisplayName("a slow issuer is abandoned at the deadline")
        void deadlineHonoured() {
            delayMillis = OidcDiscoveryService.REQUEST_TIMEOUT.toMillis() + 2_000;

            long started = System.nanoTime();
            assertThat(allowLoopback.discover(issuer)).isEmpty();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(OidcDiscoveryService.REQUEST_TIMEOUT.plusSeconds(2));
        }

        @Test
        @DisplayName("a failed discovery falls back to the overrides")
        void refusedFallsBackToOverrides() {
            ResolvedEndpoints resolved = service.resolve(issuer, new EndpointOverrides(
                    issuer + "/a", issuer + "/t", null, null, null));

            assertThat(resolved.discoveryStatus()).isEqualTo("manual");
            assertThat(resolved.tokenUri()).isEqualTo(issuer + "/t");
            assertThat(requests.get()).isZero();
        }
    }

    @Nested
    @DisplayName("discover")
    class Discover {
        @Test
        void shouldReturnEmptyForNullIssuer() {
            assertThat(service.discover(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForBlankIssuer() {
            assertThat(service.discover("")).isEmpty();
            assertThat(service.discover("   ")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForUnreachableIssuer() {
            // Non-existent issuer will fail the HTTP call
            assertThat(service.discover("https://nonexistent.example.com")).isEmpty();
        }
    }

    @Nested
    @DisplayName("resolve with overrides")
    class Resolve {
        @Test
        void shouldReturnManualStatusWhenDiscoveryFails() {
            EndpointOverrides overrides = new EndpointOverrides(
                    "https://auth.example.com/authorize",
                    "https://auth.example.com/token",
                    "https://auth.example.com/userinfo",
                    "https://auth.example.com/jwks",
                    "https://auth.example.com/logout"
            );
            ResolvedEndpoints resolved = service.resolve("https://nonexistent.example.com", overrides);
            assertThat(resolved.authorizationUri()).isEqualTo("https://auth.example.com/authorize");
            assertThat(resolved.tokenUri()).isEqualTo("https://auth.example.com/token");
            assertThat(resolved.discoveryStatus()).isEqualTo("manual");
        }

        @Test
        void shouldReturnNullFieldsWhenNoOverridesAndDiscoveryFails() {
            EndpointOverrides overrides = new EndpointOverrides(null, null, null, null, null);
            ResolvedEndpoints resolved = service.resolve("https://nonexistent.example.com", overrides);
            assertThat(resolved.authorizationUri()).isNull();
            assertThat(resolved.tokenUri()).isNull();
            assertThat(resolved.discoveryStatus()).isEqualTo("manual");
        }
    }

    @Nested
    @DisplayName("EndpointOverrides")
    class EndpointOverridesTest {
        @Test
        void hasAnyOverrideShouldReturnFalseForAllNull() {
            assertThat(new EndpointOverrides(null, null, null, null, null).hasAnyOverride()).isFalse();
        }

        @Test
        void hasAnyOverrideShouldReturnFalseForAllBlank() {
            assertThat(new EndpointOverrides("", " ", "", "", "").hasAnyOverride()).isFalse();
        }

        @Test
        void hasAnyOverrideShouldReturnTrueWhenAnySet() {
            assertThat(new EndpointOverrides(null, "https://example.com/token", null, null, null)
                    .hasAnyOverride()).isTrue();
        }
    }

    @Nested
    @DisplayName("cache")
    class Cache {
        @Test
        void evictShouldClearSpecificEntry() {
            // Exercise cache behavior: discover, evict, discover again
            service.discover("https://nonexistent1.example.com");
            service.evict("https://nonexistent1.example.com");
            // Should not throw and should attempt fetch again (not cached)
            assertThat(service.discover("https://nonexistent1.example.com")).isEmpty();
        }

        @Test
        void evictAllShouldClearAllEntries() {
            service.discover("https://nonexistent1.example.com");
            service.discover("https://nonexistent2.example.com");
            service.evictAll();
            // No assertion needed — verifying no exceptions
        }
    }
}
