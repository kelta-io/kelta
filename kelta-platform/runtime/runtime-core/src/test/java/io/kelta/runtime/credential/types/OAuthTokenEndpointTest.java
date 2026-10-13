package io.kelta.runtime.credential.types;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.credential.CredentialMaterial;
import io.kelta.runtime.credential.CredentialTestResult;
import io.kelta.runtime.credential.OAuthRefreshOutcome;
import io.kelta.runtime.credential.OAuthTokenState;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OAuth 2.0 credential types against a token endpoint on loopback. The guard allows
 * {@code 127.0.0.0/8} unless a test says otherwise.
 */
@DisplayName("OAuth 2.0 credential token endpoint")
class OAuthTokenEndpointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String tokenUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String lastBody;
    private volatile String lastContentType;
    private volatile int status = 200;
    private volatile String responseBody =
            "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"rt-2\"}";
    private volatile String location;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("client_credentials: exchanges the client secret for a token at an allow-listed URL")
    void clientCredentialsExchange() {
        OAuth2ClientCredentialsCredentialType type = clientCredentials(guarded("127.0.0.0/8"));

        OAuthRefreshOutcome outcome = type.refresh(material(), metadata(tokenUrl), null);

        assertEquals("at-1", outcome.accessToken());
        assertNotNull(outcome.expiresAt());
        assertFalse(outcome.rawResponse().has("access_token"));
        assertTrue(lastBody.startsWith("grant_type=client_credentials"), lastBody);
        assertTrue(lastBody.contains("client_id=cid"), lastBody);
        assertEquals("application/x-www-form-urlencoded", lastContentType);
    }

    @Test
    @DisplayName("client_credentials: a loopback token URL is refused by default and nothing is sent")
    void clientCredentialsRefusedByDefault() {
        ObjectProvider<OutboundUrlGuard> none = providerOf(null);
        OAuth2ClientCredentialsCredentialType type = new OAuth2ClientCredentialsCredentialType(objectMapper, none);

        CredentialTestResult result = type.test(material(), metadata(tokenUrl));

        assertFalse(result.ok());
        assertTrue(result.message().contains("refused"), result.message());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("client_credentials: the service's guard bean decides what is reachable")
    void clientCredentialsUsesGuardBean() {
        OAuth2ClientCredentialsCredentialType type = new OAuth2ClientCredentialsCredentialType(
                objectMapper, providerOf(new OutboundUrlGuard("127.0.0.0/8")));

        CredentialTestResult result = type.test(material(), metadata(tokenUrl));

        assertTrue(result.ok(), result.message());
    }

    @Test
    @DisplayName("client_credentials: a redirect is not followed")
    void redirectNotFollowed() {
        status = 302;
        responseBody = null;
        location = "http://10.0.0.1/token";

        CredentialTestResult result = clientCredentials(guarded("127.0.0.0/8"))
                .test(material(), metadata(tokenUrl));

        assertFalse(result.ok());
        assertTrue(result.message().contains("HTTP 302"), result.message());
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("client_credentials: an oversized token response is refused")
    void oversizedResponseRefused() {
        responseBody = "{\"access_token\":\"" + "x".repeat(OAuthTokenEndpointClient.MAX_RESPONSE_BYTES) + "\"}";

        CredentialTestResult result = clientCredentials(guarded("127.0.0.0/8"))
                .test(material(), metadata(tokenUrl));

        assertFalse(result.ok());
        assertTrue(result.message().contains("larger than"), result.message());
    }

    @Test
    @DisplayName("client_credentials: the deadline is honoured")
    void deadlineHonoured() {
        delayMillis = 2_000;
        OAuth2ClientCredentialsCredentialType type = new OAuth2ClientCredentialsCredentialType(objectMapper,
                new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"),
                        new OutboundHttpClient.Limits(Duration.ofSeconds(1), Duration.ofMillis(300), 1024, 0)));

        CredentialTestResult result = type.test(material(), metadata(tokenUrl));

        assertFalse(result.ok());
        assertTrue(result.message().contains("timed out"), result.message());
    }

    @Test
    @DisplayName("authorization_code: refresh posts the refresh token and keeps the new one")
    void authorizationCodeRefresh() {
        OAuth2AuthorizationCodeCredentialType type =
                new OAuth2AuthorizationCodeCredentialType(objectMapper, guarded("127.0.0.0/8"));

        OAuthRefreshOutcome outcome = type.refresh(material(), metadata(tokenUrl),
                new OAuthTokenState("old", "rt-1", "Bearer", null, null));

        assertEquals("at-1", outcome.accessToken());
        assertEquals("rt-2", outcome.refreshToken());
        assertTrue(lastBody.startsWith("grant_type=refresh_token&refresh_token=rt-1"), lastBody);
    }

    @Test
    @DisplayName("authorization_code: a non-public token URL is refused for code exchange")
    void authorizationCodeExchangeRefused() {
        OAuth2AuthorizationCodeCredentialType type =
                new OAuth2AuthorizationCodeCredentialType(objectMapper, guarded(""));

        RuntimeException e = assertThrows(RuntimeException.class, () -> type.exchangeCode(
                material().plaintext(), metadata(tokenUrl), "code-1", "https://app.example.com/cb"));

        assertTrue(e.getMessage().contains("refused"), e.getMessage());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("authorization_code: an error status fails with the endpoint's response")
    void authorizationCodeErrorStatus() {
        status = 400;
        responseBody = "{\"error\":\"invalid_grant\"}";
        OAuth2AuthorizationCodeCredentialType type =
                new OAuth2AuthorizationCodeCredentialType(objectMapper, guarded("127.0.0.0/8"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> type.refresh(
                material(), metadata(tokenUrl), new OAuthTokenState("old", "rt-1", null, null, null)));

        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
        assertTrue(e.getMessage().contains("invalid_grant"), e.getMessage());
    }

    private OAuth2ClientCredentialsCredentialType clientCredentials(OutboundHttpClient client) {
        return new OAuth2ClientCredentialsCredentialType(objectMapper, client);
    }

    private static OutboundHttpClient guarded(String allowedCidrs) {
        return new OutboundHttpClient(new OutboundUrlGuard(allowedCidrs), new OutboundHttpClient.Limits(
                OAuthTokenEndpointClient.CONNECT_TIMEOUT, OAuthTokenEndpointClient.REQUEST_DEADLINE,
                OAuthTokenEndpointClient.MAX_RESPONSE_BYTES, 0));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OutboundUrlGuard> providerOf(OutboundUrlGuard guard) {
        ObjectProvider<OutboundUrlGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        return provider;
    }

    private CredentialMaterial material() {
        ObjectNode plaintext = objectMapper.createObjectNode();
        plaintext.put("clientId", "cid");
        plaintext.put("clientSecret", "secret");
        return new CredentialMaterial("oauth2", plaintext);
    }

    private ObjectNode metadata(String url) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("tokenUrl", url);
        return metadata;
    }
}
