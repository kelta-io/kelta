package io.kelta.worker.service.storage;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.storage.RestExecutor.RestRequest;
import io.kelta.runtime.storage.RestExecutor.RestResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The external-rest executor against a real HTTP server on loopback. The guard allows
 * {@code 127.0.0.0/8} unless a test says otherwise.
 */
@DisplayName("RestClientExecutor")
class RestClientExecutorTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String lastMethod;
    private volatile String lastBody;
    private volatile String lastAuthorization;
    private volatile int status = 200;
    private volatile String responseBody = "[{\"id\":\"1\"}]";
    private volatile String location;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastMethod = exchange.getRequestMethod();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
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
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static RestClientExecutor allowingLoopback() {
        return new RestClientExecutor(new OutboundUrlGuard("127.0.0.0/8"));
    }

    @Test
    @DisplayName("sends method, headers and body and returns status and body")
    void exchanges() {
        status = 201;
        responseBody = "{\"id\":\"9\"}";

        RestResponse response = allowingLoopback().exchange(new RestRequest("POST", base + "/items",
                Map.of("Authorization", "Bearer t", "Content-Type", "application/json"), "{\"a\":1}"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"id\":\"9\"}");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastBody).isEqualTo("{\"a\":1}");
        assertThat(lastAuthorization).isEqualTo("Bearer t");
    }

    @Test
    @DisplayName("returns a non-2xx status instead of throwing")
    void nonSuccessReturned() {
        status = 404;
        responseBody = null;

        RestResponse response = allowingLoopback().exchange(new RestRequest("GET", base + "/items/x", Map.of(), null));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("refuses a loopback base URL by default and sends nothing")
    void loopbackRefusedByDefault() {
        RestClientExecutor executor = new RestClientExecutor(new OutboundUrlGuard(""));

        assertThatThrownBy(() -> executor.exchange(new RestRequest("GET", base + "/items", Map.of(), null)))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class)
                .hasMessageContaining("non-public");
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("refuses a GET redirect to a non-public address")
    void redirectToNonPublicRefused() {
        status = 302;
        responseBody = null;
        location = "http://10.0.0.1/items";

        assertThatThrownBy(() -> allowingLoopback().exchange(new RestRequest("GET", base + "/items", Map.of(), null)))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("fails on a response over the size cap")
    void oversizedResponseFails() {
        responseBody = "[" + "\"x\",".repeat(32) + "\"x\"]";
        RestClientExecutor small = new RestClientExecutor(new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"),
                new OutboundHttpClient.Limits(Duration.ofSeconds(2), Duration.ofSeconds(5), 16, 0)));

        assertThatThrownBy(() -> small.exchange(new RestRequest("GET", base + "/items", Map.of(), null)))
                .isInstanceOf(OutboundHttpClient.OutboundRequestException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    @DisplayName("honours the request deadline")
    void deadlineHonoured() {
        delayMillis = 2_000;
        RestClientExecutor quick = new RestClientExecutor(new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"),
                new OutboundHttpClient.Limits(Duration.ofSeconds(1), Duration.ofMillis(300), 1024, 0)));

        assertThatThrownBy(() -> quick.exchange(new RestRequest("GET", base + "/slow", Map.of(), null)))
                .isInstanceOf(OutboundHttpClient.OutboundRequestException.class)
                .hasMessageContaining("timed out");
    }
}
