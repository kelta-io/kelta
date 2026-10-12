package io.kelta.worker.listener;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.OutboundUrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the hook against a real HTTP server on loopback. The guard is configured to allow
 * {@code 127.0.0.0/8} for these tests; {@link #loopbackBlockedByDefault()} shows the production
 * default refuses it.
 */
@DisplayName("OidcDiscoveryHook")
class OidcDiscoveryHookTest {

    private HttpServer server;
    private String issuer;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body;
    private volatile String location;

    private OidcDiscoveryHook hook;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
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
        body = document(issuer);
        hook = new OidcDiscoveryHook(JsonMapper.builder().build(), new OutboundUrlGuard("127.0.0.0/8"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String document(String issuer) {
        return """
                {"issuer":"%1$s",
                 "jwks_uri":"%1$s/certs",
                 "authorization_endpoint":"%1$s/auth",
                 "token_endpoint":"%1$s/token",
                 "userinfo_endpoint":"%1$s/userinfo",
                 "end_session_endpoint":"%1$s/logout"}
                """.formatted(issuer);
    }

    private Map<String, Object> newProvider() {
        Map<String, Object> record = new HashMap<>();
        record.put("name", "Provider");
        record.put("issuer", issuer);
        for (String field : OidcDiscoveryHook.ENDPOINTS.keySet()) {
            record.put(field, ""); // the admin form sends empty overrides
        }
        return record;
    }

    @Test
    @DisplayName("targets oidc-providers and runs after the secret hook")
    void collectionAndOrder() {
        assertEquals("oidc-providers", hook.getCollectionName());
        assertEquals(-50, hook.getOrder());
    }

    @Test
    @DisplayName("create fills every blank endpoint and marks the provider discovered")
    void createFillsEndpoints() {
        BeforeSaveResult result = hook.beforeCreate(newProvider(), "tenant-1");

        Map<String, Object> updates = result.getFieldUpdates();
        assertEquals("discovered", updates.get("discoveryStatus"));
        assertEquals(issuer + "/certs", updates.get("jwksUri"));
        assertEquals(issuer + "/auth", updates.get("authorizationUri"));
        assertEquals(issuer + "/token", updates.get("tokenUri"));
        assertEquals(issuer + "/userinfo", updates.get("userinfoUri"));
        assertEquals(issuer + "/logout", updates.get("endSessionUri"));
    }

    @Test
    @DisplayName("an administrator's endpoint override is kept")
    void keepsOverrides() {
        Map<String, Object> record = newProvider();
        record.put("tokenUri", "https://override.example.com/token");

        Map<String, Object> updates = hook.beforeCreate(record, "tenant-1").getFieldUpdates();

        assertFalse(updates.containsKey("tokenUri"));
        assertEquals(issuer + "/auth", updates.get("authorizationUri"));
    }

    @Test
    @DisplayName("a trailing slash on the issuer still finds the document and matches it")
    void trailingSlash() {
        Map<String, Object> record = newProvider();
        record.put("issuer", issuer + "/");

        assertEquals("discovered", hook.beforeCreate(record, "tenant-1").getFieldUpdates().get("discoveryStatus"));
    }

    @Test
    @DisplayName("a caller-supplied discoveryStatus is dropped")
    void callerCannotSetStatus() {
        Map<String, Object> record = newProvider();
        record.put("issuer", "");
        record.put("discoveryStatus", "discovered");

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(record.containsKey("discoveryStatus"));
        assertFalse(result.hasFieldUpdates());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("a non-200 response is an error that does not block the save")
    void non200IsError() {
        status = 404;

        BeforeSaveResult result = hook.beforeCreate(newProvider(), "tenant-1");

        assertTrue(result.isSuccess());
        assertEquals(Map.of("discoveryStatus", "error"), result.getFieldUpdates());
    }

    @Test
    @DisplayName("a redirect is not followed")
    void redirectNotFollowed() {
        status = 302;
        location = "http://169.254.169.254/latest/meta-data/";

        BeforeSaveResult result = hook.beforeCreate(newProvider(), "tenant-1");

        assertEquals("error", result.getFieldUpdates().get("discoveryStatus"));
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("a document for a different issuer is rejected")
    void issuerMismatch() {
        body = document("https://other-issuer.example.com");

        assertEquals(Map.of("discoveryStatus", "error"),
                hook.beforeCreate(newProvider(), "tenant-1").getFieldUpdates());
    }

    @Test
    @DisplayName("an oversized document is rejected")
    void oversized() {
        body = "{\"issuer\":\"" + issuer + "\",\"pad\":\""
                + "x".repeat(OidcDiscoveryHook.MAX_DOCUMENT_BYTES) + "\"}";

        assertEquals("error", hook.beforeCreate(newProvider(), "tenant-1").getFieldUpdates().get("discoveryStatus"));
    }

    @Test
    @DisplayName("a response that is not a JSON object is an error")
    void notJson() {
        body = "<html>not json</html>";

        assertEquals("error", hook.beforeCreate(newProvider(), "tenant-1").getFieldUpdates().get("discoveryStatus"));
    }

    @Test
    @DisplayName("an unreachable issuer is an error")
    void unreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        Map<String, Object> record = newProvider();
        record.put("issuer", "http://127.0.0.1:" + closedPort);

        assertEquals("error", hook.beforeCreate(record, "tenant-1").getFieldUpdates().get("discoveryStatus"));
    }

    @Test
    @DisplayName("discovered values that are not http(s) URLs or overflow the column are not stored")
    void rejectsUnusableEndpointValues() {
        body = """
                {"issuer":"%1$s",
                 "jwks_uri":"file:///etc/passwd",
                 "authorization_endpoint":"not a url",
                 "token_endpoint":"%1$s/%2$s",
                 "userinfo_endpoint":42,
                 "end_session_endpoint":"%1$s/logout"}
                """.formatted(issuer, "t".repeat(OidcDiscoveryHook.MAX_ENDPOINT_LENGTH));

        Map<String, Object> updates = hook.beforeCreate(newProvider(), "tenant-1").getFieldUpdates();

        assertEquals("discovered", updates.get("discoveryStatus"));
        assertEquals(issuer + "/logout", updates.get("endSessionUri"));
        assertFalse(updates.containsKey("jwksUri"));
        assertFalse(updates.containsKey("authorizationUri"));
        assertFalse(updates.containsKey("tokenUri"));
        assertFalse(updates.containsKey("userinfoUri"));
    }

    @Test
    @DisplayName("with the production default, a loopback issuer is blocked and never fetched")
    void loopbackBlockedByDefault() {
        OidcDiscoveryHook guarded = new OidcDiscoveryHook(JsonMapper.builder().build(), new OutboundUrlGuard(""));

        BeforeSaveResult result = guarded.beforeCreate(newProvider(), "tenant-1");

        assertTrue(result.isSuccess());
        assertEquals(Map.of("discoveryStatus", "blocked"), result.getFieldUpdates());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("an issuer whose host does not resolve is an error, not blocked")
    void unresolvableIsError() {
        Map<String, Object> record = newProvider();
        record.put("issuer", "https://idp.does-not-exist.invalid");

        assertEquals(Map.of("discoveryStatus", "error"), hook.beforeCreate(record, "tenant-1").getFieldUpdates());
    }

    @Test
    @DisplayName("update with the same, already discovered issuer does not refetch")
    void unchangedDiscoveredIssuerSkipped() {
        Map<String, Object> previous = Map.of("issuer", issuer, "discoveryStatus", "discovered");
        Map<String, Object> patch = new HashMap<>(Map.of("issuer", issuer, "name", "Renamed"));

        BeforeSaveResult result = hook.beforeUpdate("id-1", patch, previous, "tenant-1");

        assertFalse(result.hasFieldUpdates());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("update retries when the last discovery did not succeed, even without an issuer in the patch")
    void retriesAfterError() {
        Map<String, Object> previous = Map.of("issuer", issuer, "discoveryStatus", "error");

        BeforeSaveResult result = hook.beforeUpdate("id-1", new HashMap<>(Map.of("name", "Renamed")),
                previous, "tenant-1");

        assertEquals("discovered", result.getFieldUpdates().get("discoveryStatus"));
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("on an issuer change, endpoints discovered from the old issuer are replaced; overrides are kept")
    void issuerChangeReplacesStaleEndpoints() {
        Map<String, Object> previous = new HashMap<>();
        previous.put("issuer", "https://old-issuer.example.com");
        previous.put("discoveryStatus", "discovered");
        previous.put("jwksUri", "https://old-issuer.example.com/certs");
        previous.put("tokenUri", "https://old-issuer.example.com/token");
        previous.put("authorizationUri", "https://old-issuer.example.com/auth");
        Map<String, Object> patch = new HashMap<>();
        patch.put("issuer", issuer);
        patch.put("jwksUri", "https://old-issuer.example.com/certs");       // untouched in the form
        patch.put("tokenUri", "https://override.example.com/token");        // edited by the admin
        // authorizationUri not in the patch at all

        Map<String, Object> updates = hook.beforeUpdate("id-1", patch, previous, "tenant-1").getFieldUpdates();

        assertEquals("discovered", updates.get("discoveryStatus"));
        assertEquals(issuer + "/certs", updates.get("jwksUri"));
        assertEquals(issuer + "/auth", updates.get("authorizationUri"));
        assertFalse(updates.containsKey("tokenUri"), "the admin's new override stays");
    }

    @Test
    @DisplayName("on an issuer change that cannot be discovered, the old issuer's endpoints are cleared")
    void issuerChangeClearsStaleEndpointsOnFailure() {
        status = 500;
        Map<String, Object> previous = new HashMap<>();
        previous.put("issuer", "https://old-issuer.example.com");
        previous.put("discoveryStatus", "discovered");
        previous.put("jwksUri", "https://old-issuer.example.com/certs");
        Map<String, Object> patch = new HashMap<>(Map.of("issuer", issuer));

        Map<String, Object> updates = hook.beforeUpdate("id-1", patch, previous, "tenant-1").getFieldUpdates();

        assertEquals("error", updates.get("discoveryStatus"));
        assertTrue(updates.containsKey("jwksUri"));
        assertNull(updates.get("jwksUri"));
    }

    @Test
    @DisplayName("on an issuer change, endpoints set while discovery had not succeeded are overrides")
    void issuerChangeKeepsManualEndpoints() {
        Map<String, Object> previous = new HashMap<>();
        previous.put("issuer", "https://old-issuer.example.com");
        previous.put("discoveryStatus", "unknown");
        previous.put("jwksUri", "https://manual.example.com/certs");
        Map<String, Object> patch = new HashMap<>(Map.of("issuer", issuer));

        Map<String, Object> updates = hook.beforeUpdate("id-1", patch, previous, "tenant-1").getFieldUpdates();

        assertFalse(updates.containsKey("jwksUri"));
        assertEquals(issuer + "/token", updates.get("tokenUri"));
    }
}
