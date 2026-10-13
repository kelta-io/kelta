package io.kelta.runtime.outbound;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.outbound.OutboundHttpClient.Limits;
import io.kelta.runtime.outbound.OutboundHttpClient.OutboundRequestException;
import io.kelta.runtime.outbound.OutboundHttpClient.Request;
import io.kelta.runtime.outbound.OutboundHttpClient.Response;
import io.kelta.runtime.outbound.OutboundUrlGuard.Reason;
import io.kelta.runtime.outbound.OutboundUrlGuard.RejectedUrlException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drives the client against real HTTP servers on loopback. */
@DisplayName("OutboundHttpClient")
class OutboundHttpClientTest {

    private static final Limits LIMITS = new Limits(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024, 3);

    private HttpServer server;
    private HttpServer otherServer;
    private String base;
    private String otherBase;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<String, Consumer<HttpExchange>> routes = new ConcurrentHashMap<>();
    private final Map<String, String> lastAuthorization = new ConcurrentHashMap<>();

    private final OutboundHttpClient allowLoopback =
            new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"), LIMITS);

    @BeforeEach
    void setUp() throws IOException {
        server = start("main");
        otherServer = start("other");
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        otherBase = "http://127.0.0.1:" + otherServer.getAddress().getPort();
    }

    private HttpServer start(String name) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", exchange -> {
            requests.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            lastAuthorization.put(name + exchange.getRequestURI().getPath(), auth == null ? "<none>" : auth);
            Consumer<HttpExchange> route = routes.get(name + exchange.getRequestURI().getPath());
            if (route == null) {
                respond(exchange, 404, "not found");
            } else {
                route.accept(exchange);
            }
        });
        s.start();
        return s;
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        otherServer.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Consumer<HttpExchange> redirectTo(String location) {
        return exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            respond(exchange, 302, "");
        };
    }

    @Test
    @DisplayName("fetches an allow-listed private address")
    void fetchesAllowListed() {
        routes.put("main/doc", exchange -> respond(exchange, 200, "{\"ok\":true}"));

        Response response = allowLoopback.get(base + "/doc", Map.of("Accept", "application/json"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.bodyAsString()).isEqualTo("{\"ok\":true}");
        assertThat(response.truncated()).isFalse();
    }

    @Test
    @DisplayName("refuses loopback by default and sends nothing")
    void loopbackRefusedByDefault() {
        routes.put("main/doc", exchange -> respond(exchange, 200, "{}"));
        OutboundHttpClient client = new OutboundHttpClient(new OutboundUrlGuard(""), LIMITS);

        assertThatThrownBy(() -> client.get(base + "/doc", Map.of()))
                .isInstanceOf(RejectedUrlException.class)
                .extracting(e -> ((RejectedUrlException) e).reason())
                .isEqualTo(Reason.NON_PUBLIC_ADDRESS);
        assertThat(requests.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", "http://10.0.0.1/", "http://192.168.1.1/",
            "http://[::ffff:169.254.169.254]/", "http://[fd00::1]/"})
    @DisplayName("refuses link-local, private and embedded forms even when loopback is allowed")
    void refusesOtherNonPublic(String url) {
        assertThatThrownBy(() -> allowLoopback.get(url, Map.of()))
                .isInstanceOf(RejectedUrlException.class);
    }

    @Test
    @DisplayName("re-validates a redirect and refuses one to a non-public address")
    void redirectToNonPublicRefused() {
        routes.put("main/start", redirectTo("http://169.254.169.254/latest/meta-data/"));

        assertThatThrownBy(() -> allowLoopback.get(base + "/start", Map.of()))
                .isInstanceOf(RejectedUrlException.class)
                .extracting(e -> ((RejectedUrlException) e).reason())
                .isEqualTo(Reason.NON_PUBLIC_ADDRESS);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("follows an allowed redirect and reports the final URL")
    void followsAllowedRedirect() {
        routes.put("main/start", redirectTo("/end"));
        routes.put("main/end", exchange -> respond(exchange, 200, "done"));

        Response response = allowLoopback.get(base + "/start", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString()).isEqualTo("done");
        assertThat(response.uri().getPath()).isEqualTo("/end");
    }

    @Test
    @DisplayName("stops after the redirect limit")
    void redirectLimit() {
        routes.put("main/loop", redirectTo("/loop"));

        assertThatThrownBy(() -> allowLoopback.get(base + "/loop", Map.of()))
                .isInstanceOf(OutboundRequestException.class)
                .extracting(e -> ((OutboundRequestException) e).kind())
                .isEqualTo(OutboundRequestException.Kind.TOO_MANY_REDIRECTS);
        assertThat(requests.get()).isEqualTo(LIMITS.maxRedirects() + 1);
    }

    @Test
    @DisplayName("with no redirects allowed, a redirect is returned as-is and not followed")
    void noRedirectsReturnsRedirect() {
        routes.put("main/start", redirectTo("/end"));
        OutboundHttpClient client = new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"),
                new Limits(Duration.ofSeconds(2), Duration.ofSeconds(5), 1024, 0));

        Response response = client.get(base + "/start", Map.of());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("does not follow a redirect for a POST")
    void postDoesNotFollow() {
        routes.put("main/start", redirectTo("/end"));

        Response response = allowLoopback.send(new Request("POST", base + "/start",
                Map.of("Content-Type", "application/json"), "{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("drops credential headers on a redirect to another origin")
    void crossOriginRedirectDropsCredentials() {
        routes.put("main/start", redirectTo(otherBase + "/end"));
        routes.put("other/end", exchange -> respond(exchange, 200, "ok"));

        allowLoopback.get(base + "/start", Map.of("Authorization", "Bearer secret"));

        assertThat(lastAuthorization.get("main/start")).isEqualTo("Bearer secret");
        assertThat(lastAuthorization.get("other/end")).isEqualTo("<none>");
    }

    @Test
    @DisplayName("keeps at most the configured bytes and marks the response truncated")
    void capsBody() {
        routes.put("main/big", exchange -> respond(exchange, 200, "x".repeat(10_000)));

        Response response = allowLoopback.get(base + "/big", Map.of());

        assertThat(response.truncated()).isTrue();
        assertThat(response.body()).hasSize(LIMITS.maxResponseBytes());
    }

    @Test
    @DisplayName("a body of exactly the cap is not truncated")
    void bodyAtCap() {
        routes.put("main/exact", exchange -> respond(exchange, 200, "y".repeat(LIMITS.maxResponseBytes())));

        Response response = allowLoopback.get(base + "/exact", Map.of());

        assertThat(response.truncated()).isFalse();
        assertThat(response.body()).hasSize(LIMITS.maxResponseBytes());
    }

    @Test
    @DisplayName("honours the overall deadline")
    void deadline() {
        routes.put("main/slow", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        OutboundHttpClient client = new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"),
                new Limits(Duration.ofSeconds(1), Duration.ofMillis(300), 1024, 0));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get(base + "/slow", Map.of()))
                .isInstanceOf(OutboundRequestException.class)
                .extracting(e -> ((OutboundRequestException) e).kind())
                .isEqualTo(OutboundRequestException.Kind.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("skips headers the HTTP client manages itself instead of failing")
    void skipsRestrictedHeaders() {
        routes.put("main/doc", exchange -> respond(exchange, 200, "ok"));

        Response response = allowLoopback.get(base + "/doc", Map.of("Host", "elsewhere.example", "Connection", "close"));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("rejects invalid limits")
    void invalidLimits() {
        assertThatThrownBy(() -> new Limits(Duration.ZERO, Duration.ofSeconds(1), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(Duration.ofSeconds(1), Duration.ofSeconds(1), 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
