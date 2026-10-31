package io.kelta.worker.module;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.outbound.ModuleHostGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModuleHttpClient")
class ModuleHttpClientTest {

    private static final String ISSUER = "https://api.example.test/api/modules";

    private HttpServer server;
    private String baseUrl;
    private final Map<String, Map<String, List<String>>> requestHeaders = new ConcurrentHashMap<>();
    private final Map<String, byte[]> requestBodies = new ConcurrentHashMap<>();
    private final Map<String, String> requestQueries = new ConcurrentHashMap<>();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private final ModuleAssertionSigner signer = new ModuleAssertionSigner(ModuleAssertionSignerTest.KEY_PEM, ISSUER);
    private ModuleHttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ok/.well-known/kelta-module.json", exchange -> {
            byte[] body = "{\"manifestVersion\":2}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/moved/.well-known/kelta-module.json", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/ok/kelta/v1/lifecycle", exchange -> {
            requestHeaders.put("lifecycle", Map.copyOf(exchange.getRequestHeaders()));
            requestBodies.put("lifecycle", exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/broken/kelta/v1/lifecycle", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":\"not ready\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ok/kelta/v1/routes/report", exchange -> {
            requestHeaders.put("route", Map.copyOf(exchange.getRequestHeaders()));
            requestBodies.put("route", exchange.getRequestBody().readAllBytes());
            requestQueries.put("route", String.valueOf(exchange.getRequestURI().getRawQuery()));
            byte[] body = "{\"rows\":3}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(418, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/slow/kelta/v1/actions/ping", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/ok/ui/bundle.js", exchange -> {
            requestHeaders.put("bundle", Map.copyOf(exchange.getRequestHeaders()));
            byte[] body = "console.log('ui')".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/javascript");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ok/ui/huge.js", exchange -> {
            byte[] chunk = new byte[64 * 1024];
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (int written = 0; written <= ModuleHttpClient.MAX_UI_BUNDLE_BYTES; written += chunk.length) {
                    out.write(chunk);
                }
            } catch (java.io.IOException ignored) {
                // the client stops reading at its cap
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        client = new ModuleHttpClient(new ModuleHostGuard("localhost"), signer, new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TenantModuleData module(String path) {
        return new TenantModuleData("install-1", "tenant-1", "acme-billing", "Billing", "1.0.0", null,
                baseUrl + path, null, null, null, "{}", TenantModuleData.STATUS_INSTALLING, "user-1",
                null, null, null, List.of(), baseUrl + path, 1, "app-1", "svc-1");
    }

    @Test
    @DisplayName("fetches the manifest from {baseUrl}/.well-known/kelta-module.json with no credential")
    void fetchesTheManifest() {
        assertThat(client.fetchManifest(baseUrl + "/ok")).isEqualTo("{\"manifestVersion\":2}");
    }

    @Test
    @DisplayName("fetches the UI bundle with no credential; a missing or oversized bundle is a failure")
    void fetchesTheUiBundle() {
        assertThat(new String(client.fetchUiBundle(baseUrl + "/ok", "/ui/bundle.js"), StandardCharsets.UTF_8))
                .isEqualTo("console.log('ui')");
        assertThat(requestHeaders.get("bundle")).doesNotContainKeys("Authorization", "Kelta-access-token");

        assertThatThrownBy(() -> client.fetchUiBundle(baseUrl + "/ok", "/ui/missing.js"))
                .isInstanceOf(ModuleHttpClient.ModuleCallException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> client.fetchUiBundle(baseUrl + "/ok", "/ui/huge.js"))
                .isInstanceOf(ModuleHttpClient.ModuleCallException.class).hasMessageContaining("larger than");
    }

    @Test
    @DisplayName("never follows a redirect: a 3xx is a failure and the target is not requested")
    void noRedirects() {
        assertThatThrownBy(() -> client.fetchManifest(baseUrl + "/moved"))
                .isInstanceOf(ModuleHttpClient.ModuleCallException.class)
                .hasMessageContaining("302")
                .hasMessageContaining("redirects are not followed");
        assertThat(redirectTargetHits).hasValue(0);
    }

    @Test
    @DisplayName("lifecycle sends a verifiable assertion over the exact URL and body, plus the access token")
    void lifecycleIsSigned() throws Exception {
        client.lifecycle(module("/ok"), "acme", ModuleHttpClient.LifecycleEvent.of("installed", module("/ok")),
                "service-token");

        Map<String, List<String>> headers = requestHeaders.get("lifecycle");
        assertThat(headers.get("Kelta-access-token")).containsExactly("service-token");
        String authorization = headers.get("Authorization").getFirst();
        assertThat(authorization).startsWith("Bearer ");
        SignedJWT jwt = SignedJWT.parse(authorization.substring("Bearer ".length()));
        ECKey key = (ECKey) JWKSet.parse(signer.jwks()).getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(jwt.verify(new ECDSAVerifier(key))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("acme-billing");
        assertThat(claims.getSubject()).isEqualTo("install-1");
        assertThat(claims.getStringClaim("tenant_slug")).isEqualTo("acme");
        assertThat(claims.getStringClaim("htm")).isEqualTo("POST");
        assertThat(claims.getStringClaim("htu")).isEqualTo(baseUrl + "/ok/kelta/v1/lifecycle");
        byte[] received = requestBodies.get("lifecycle");
        assertThat(claims.getStringClaim("body_sha256")).isEqualTo(ModuleAssertionSigner.bodySha256(received));
        assertThat(new String(received, StandardCharsets.UTF_8))
                .contains("\"event\":\"installed\"", "\"tenantId\":\"tenant-1\"", "\"moduleVersion\":\"1.0.0\"");
    }

    @Test
    @DisplayName("a non-2xx lifecycle answer is a failure carrying the status and the start of the body")
    void lifecycleFailure() {
        assertThatThrownBy(() -> client.lifecycle(module("/broken"), "acme",
                ModuleHttpClient.LifecycleEvent.of("installed", module("/broken")), "service-token"))
                .isInstanceOf(ModuleHttpClient.ModuleCallException.class)
                .hasMessageContaining("503")
                .hasMessageContaining("not ready")
                .extracting(e -> ((ModuleHttpClient.ModuleCallException) e).status()).isEqualTo(503);
    }

    @Test
    @DisplayName("invoke signs the exact URL with its query, forwards headers and the actor, and returns any status")
    void invokeSignsTheExactUrl() throws Exception {
        byte[] body = "{\"q\":1}".getBytes(StandardCharsets.UTF_8);
        var response = client.invoke(module("/ok"), "acme", new ModuleHttpClient.Call("POST",
                "/kelta/v1/routes/report", "from=2026-01-01&to=x%20y", body, "application/json",
                Map.of("Idempotency-Key", "exec-1:Step"), java.time.Duration.ofSeconds(5),
                new ModuleAssertionSigner.Actor("user-1", "INTERNAL", "profile-1")), "service-token");

        // Any status is the caller's to judge (ExternalModuleInvoker); nothing here throws on a 418.
        assertThat(response.statusCode()).isEqualTo(418);
        assertThat(response.bodyAsString()).isEqualTo("{\"rows\":3}");
        assertThat(requestQueries.get("route")).isEqualTo("from=2026-01-01&to=x%20y");
        Map<String, List<String>> headers = requestHeaders.get("route");
        assertThat(headers.get("Idempotency-key")).containsExactly("exec-1:Step");
        assertThat(headers.get("Kelta-access-token")).containsExactly("service-token");
        assertThat(requestBodies.get("route")).isEqualTo(body);

        SignedJWT jwt = SignedJWT.parse(headers.get("Authorization").getFirst().substring("Bearer ".length()));
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getStringClaim("htu"))
                .isEqualTo(baseUrl + "/ok/kelta/v1/routes/report?from=2026-01-01&to=x%20y");
        assertThat(claims.getStringClaim("body_sha256")).isEqualTo(ModuleAssertionSigner.bodySha256(body));
        Map<String, Object> act = claims.getJSONObjectClaim("act");
        assertThat(act).containsEntry("sub", "user-1").containsEntry("user_type", "INTERNAL")
                .containsEntry("profile_id", "profile-1");
    }

    @Test
    @DisplayName("invoke enforces the call's own timeout and says the call timed out")
    void invokeTimesOut() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.invoke(module("/slow"), "acme", ModuleHttpClient.Call.postJson(
                "/kelta/v1/actions/ping", "{}".getBytes(StandardCharsets.UTF_8), java.time.Duration.ofMillis(300),
                null), "service-token"))
                .isInstanceOf(ModuleHttpClient.ModuleCallException.class)
                .extracting(e -> ((ModuleHttpClient.ModuleCallException) e).timedOut()).isEqualTo(true);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a Call never waits longer than the protocol's 120 s cap")
    void callTimeoutIsCapped() {
        assertThat(ModuleHttpClient.Call.postJson("/kelta/v1/actions/x", null, java.time.Duration.ofMinutes(10), null)
                .timeout()).isEqualTo(java.time.Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("normalises a base URL and refuses one the module-host guard refuses")
    void normalizesBaseUrls() {
        assertThat(client.normalizeBaseUrl(" " + baseUrl + "/ok// ")).isEqualTo(baseUrl + "/ok");
        assertThatThrownBy(() -> client.normalizeBaseUrl(baseUrl + "/ok?x=1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query");
        // Not allow-listed: http and a private address are both refused.
        assertThatThrownBy(() -> client.normalizeBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refused");
        assertThatThrownBy(() -> client.normalizeBaseUrl(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
