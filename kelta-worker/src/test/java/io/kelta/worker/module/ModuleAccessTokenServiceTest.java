package io.kelta.worker.module;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.service.ModuleServicePrincipalService;
import io.kelta.worker.service.ModuleServicePrincipalService.ClientCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ModuleAccessTokenService")
class ModuleAccessTokenServiceTest {

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-30T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private HttpServer server;
    private final AtomicInteger issued = new AtomicInteger();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private final MovableClock clock = new MovableClock();
    private ModuleServicePrincipalService principals;
    private ModuleAccessTokenService tokens;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth2/token", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (status == 200
                    ? "{\"access_token\":\"token-" + issued.incrementAndGet() + "\",\"expires_in\":900}"
                    : "{\"error\":\"invalid_client\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        principals = mock(ModuleServicePrincipalService.class);
        when(principals.clientCredentials("tenant-1", "app-1"))
                .thenReturn(Optional.of(new ClientCredentials("client:1", "s3cret/+")));
        tokens = new ModuleAccessTokenService("http://localhost:" + server.getAddress().getPort() + "/",
                principals, new ObjectMapper(), clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static TenantModuleData module(String connectedAppId) {
        return new TenantModuleData("install-1", "tenant-1", "acme-billing", "Billing", "1.0.0", null,
                "https://billing.example.test", null, null, null, "{}", TenantModuleData.STATUS_ACTIVE, "user-1",
                null, null, null, List.of(), "https://billing.example.test", 1, connectedAppId, "svc-1");
    }

    @Test
    @DisplayName("client_credentials with form-encoded Basic credentials, cached until 60 s before expiry")
    void requestsAndCaches() {
        assertThat(tokens.accessToken(module("app-1"))).isEqualTo("token-1");
        assertThat(bodies.getFirst()).isEqualTo("grant_type=client_credentials");
        String basic = new String(Base64.getDecoder().decode(authorizations.getFirst().substring("Basic ".length())),
                StandardCharsets.UTF_8);
        assertThat(basic).isEqualTo("client%3A1:s3cret%2F%2B");

        clock.now = clock.now.plus(Duration.ofSeconds(839));
        assertThat(tokens.accessToken(module("app-1"))).isEqualTo("token-1");
        clock.now = clock.now.plus(Duration.ofSeconds(1));
        assertThat(tokens.accessToken(module("app-1"))).isEqualTo("token-2");
    }

    @Test
    @DisplayName("a token with a minimum validity: the cached one while it lasts that long, else a fresh one")
    void minimumValidity() {
        ModuleAccessTokenService.AccessToken first = tokens.accessToken(module("app-1"), Duration.ofMinutes(10));
        assertThat(first.token()).isEqualTo("token-1");
        assertThat(first.expiresAt()).isEqualTo(clock.now.plusSeconds(900));
        assertThat(first.toString()).doesNotContain("token-1");

        clock.now = clock.now.plus(Duration.ofMinutes(4));
        assertThat(tokens.accessToken(module("app-1"), Duration.ofMinutes(10)).token())
                .as("11 minutes left").isEqualTo("token-1");

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        ModuleAccessTokenService.AccessToken renewed = tokens.accessToken(module("app-1"), Duration.ofMinutes(10));
        assertThat(renewed.token()).as("9 minutes left is too little").isEqualTo("token-2");
        assertThat(renewed.expiresAt()).isEqualTo(clock.now.plusSeconds(900));
        assertThat(tokens.accessToken(module("app-1"))).as("the cache holds the renewed token").isEqualTo("token-2");
    }

    @Test
    @DisplayName("evict forces a fresh token on the next call")
    void evict() {
        tokens.accessToken(module("app-1"));
        tokens.evict("app-1");
        tokens.evict(null);

        assertThat(tokens.accessToken(module("app-1"))).isEqualTo("token-2");
    }

    @Test
    @DisplayName("refuses an install without a principal, without credentials, or that kelta-auth rejects")
    void refusals() {
        assertThatThrownBy(() -> tokens.accessToken(module(null))).isInstanceOf(IllegalStateException.class);
        when(principals.clientCredentials("tenant-1", "app-2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tokens.accessToken(module("app-2"))).isInstanceOf(IllegalStateException.class);

        status = 401;
        assertThatThrownBy(() -> tokens.accessToken(module("app-1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("401")
                .hasMessageNotContaining("s3cret");
    }
}
