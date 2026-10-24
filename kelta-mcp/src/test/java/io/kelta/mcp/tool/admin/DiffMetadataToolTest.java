package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * diff_metadata posts the package as the raw JSON body of {@code /api/packages/import/preview}
 * (the worker accepts that as well as the CLI's multipart file part) and never calls the import
 * endpoint.
 */
class DiffMetadataToolTest {

    private WireMockServer wm;
    private DiffMetadataTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new DiffMetadataTool(client);
        RequestPatHolder.set("klt_diff_metadata_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static final String PACKAGE = "{\"name\":\"crm\",\"version\":\"1.0.0\",\"items\":[{\"type\":\"COLLECTION\",\"name\":\"deals\"}]}";

    @Test
    void postsPackageObjectToPreviewOnly() {
        wm.stubFor(post(urlEqualTo("/api/packages/import/preview"))
                .willReturn(aResponse().withStatus(200).withBody("{\"changes\":[{\"type\":\"COLLECTION\",\"action\":\"CREATE\"}]}")));

        CallToolResult result = tool.diff(Map.of("package", Map.of(
                "name", "crm", "version", "1.0.0",
                "items", List.of(Map.of("type", "COLLECTION", "name", "deals")))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("\"action\":\"CREATE\"");
        wm.verify(postRequestedFor(urlEqualTo("/api/packages/import/preview"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(PACKAGE)));
        wm.verify(0, postRequestedFor(urlPathEqualTo("/api/packages/import")));
    }

    @Test
    void acceptsPackageAsJsonText() {
        wm.stubFor(post(urlEqualTo("/api/packages/import/preview"))
                .willReturn(aResponse().withStatus(200).withBody("{\"changes\":[]}")));

        CallToolResult result = tool.diff(Map.of("package", PACKAGE));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(postRequestedFor(urlEqualTo("/api/packages/import/preview"))
                .withRequestBody(equalToJson(PACKAGE)));
    }

    @Test
    void missingOrNonObjectPackageIsRejectedWithoutCallingGateway() {
        assertThat(tool.diff(Map.of()).isError()).isTrue();
        assertThat(tool.diff(Map.of("package", "not json")).isError()).isTrue();
        assertThat(tool.diff(Map.of("package", "[1,2]")).isError()).isTrue();
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void gatewayErrorIsSurfacedAsToolError() {
        wm.stubFor(post(urlEqualTo("/api/packages/import/preview"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"Invalid package file: bad\"}")));

        CallToolResult result = tool.diff(Map.of("package", PACKAGE));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("HTTP 400").contains("Invalid package file");
    }
}
