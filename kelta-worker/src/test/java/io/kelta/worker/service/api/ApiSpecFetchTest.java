package io.kelta.worker.service.api;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.module.integration.api.OpenApiParseException;
import io.kelta.runtime.module.integration.api.OpenApiSpecParser;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.worker.controller.ApiSpecController;
import io.kelta.worker.repository.ApiOperationRepository;
import io.kelta.worker.repository.ApiSpecRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Fetching an OpenAPI spec from a tenant-supplied {@code sourceUrl}, against a real HTTP server on
 * loopback. The guard allows {@code 127.0.0.0/8} unless a test shows the default.
 */
@DisplayName("API spec fetch from a source URL")
class ApiSpecFetchTest {

    private static final String SPEC = """
            {"openapi":"3.0.0","info":{"title":"Pets","version":"1"},
             "servers":[{"url":"https://api.example.com"}],
             "paths":{"/pets":{"get":{"operationId":"listPets","responses":{"200":{"description":"ok"}}}}}}
            """;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = SPEC;
    private volatile String location;

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
                } catch (IOException ignored) {
                    // the client stops reading at its size cap
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

    private ApiSpecService service(String allowedCidrs) {
        return new ApiSpecService(new OpenApiSpecParser(objectMapper), mock(ApiSpecRepository.class),
                mock(ApiOperationRepository.class), objectMapper, new OutboundUrlGuard(allowedCidrs));
    }

    @Test
    @DisplayName("an allow-listed private URL is fetched")
    void allowListedFetched() {
        assertThat(service("127.0.0.0/8").fetchSpec(base + "/openapi.json")).isEqualTo(SPEC);
    }

    @Test
    @DisplayName("with the default guard, a loopback URL is refused and never fetched")
    void loopbackRefusedByDefault() {
        assertThatThrownBy(() -> service("").fetchSpec(base + "/openapi.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("a link-local metadata URL is refused")
    void metadataAddressRefused() {
        assertThatThrownBy(() -> service("127.0.0.0/8").fetchSpec("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a redirect to a non-public address is refused")
    void redirectToNonPublicRefused() {
        status = 302;
        body = null;
        location = "http://192.168.1.10/openapi.json";

        assertThatThrownBy(() -> service("127.0.0.0/8").fetchSpec(base + "/openapi.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a document over the size cap is refused")
    void oversizedRefused() {
        body = "x".repeat(ApiSpecService.MAX_SPEC_BYTES + 1);

        assertThatThrownBy(() -> service("127.0.0.0/8").fetchSpec(base + "/openapi.json"))
                .isInstanceOf(OpenApiParseException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("a non-2xx response is a fetch failure")
    void non2xxIsFailure() {
        status = 404;

        assertThatThrownBy(() -> service("127.0.0.0/8").fetchSpec(base + "/missing.json"))
                .isInstanceOf(OpenApiParseException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("import from a refused URL fails before anything is stored")
    void importRefused() {
        ApiSpecService service = service("");

        assertThatThrownBy(() -> service.importSpec(new ApiSpecService.ImportRequest(
                "Pets", null, "URL", base + "/openapi.json", null, null), "tenant-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("validate refuses a non-public source URL with a 400")
    void validateRefused() {
        ApiSpecController controller = new ApiSpecController(service(""), mock(ApiOperationRepository.class),
                new OpenApiSpecParser(objectMapper), mock(ExternalEntityMaterializer.class));

        ResponseEntity<?> response = controller.validate(Map.of("sourceUrl", base + "/openapi.json"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(bodyOf(response)).containsEntry("ok", false);
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("validate parses an allow-listed source URL")
    void validateFetches() {
        ApiSpecController controller = new ApiSpecController(service("127.0.0.0/8"),
                mock(ApiOperationRepository.class), new OpenApiSpecParser(objectMapper),
                mock(ExternalEntityMaterializer.class));

        ResponseEntity<?> response = controller.validate(Map.of("sourceUrl", base + "/openapi.json"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(bodyOf(response)).containsEntry("ok", true).containsEntry("operations", 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
