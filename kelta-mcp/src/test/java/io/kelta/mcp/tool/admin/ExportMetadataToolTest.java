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
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * export_metadata posts the CLI's export body to {@code /api/packages/export} and returns the
 * package JSON as the tool's text content (not a file path).
 */
class ExportMetadataToolTest {

    private WireMockServer wm;
    private ExportMetadataTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ExportMetadataTool(client);
        RequestPatHolder.set("klt_export_metadata_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void emptyArgsPostEmptyBodyAndReturnPackageJsonAsText() {
        String pkg = "{\"name\":\"acme\",\"version\":\"1.0.0\",\"items\":[{\"type\":\"COLLECTION\",\"name\":\"deals\"}]}";
        wm.stubFor(post(urlEqualTo("/api/packages/export"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Disposition", "attachment; filename=\"acme-1.0.0.json\"")
                        .withBody(pkg)));

        CallToolResult result = tool.export(Map.of());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo(pkg);
        wm.verify(postRequestedFor(urlEqualTo("/api/packages/export"))
                .withRequestBody(equalToJson("{}")));
    }

    @Test
    void collectionsAreSentAsCollectionNamesAndExcludeAsPackageTypes() {
        wm.stubFor(post(urlEqualTo("/api/packages/export"))
                .willReturn(aResponse().withStatus(200).withBody("{\"items\":[]}")));

        CallToolResult result = tool.export(Map.of(
                "name", "crm",
                "version", "2.0.0",
                "collections", List.of("deals", " accounts "),
                "exclude", List.of("list-views", "dashboard", "PAGE_LAYOUT")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(postRequestedFor(urlEqualTo("/api/packages/export"))
                .withRequestBody(equalToJson("""
                        {"name":"crm","version":"2.0.0","collectionNames":["deals","accounts"],
                         "excludeTypes":["LIST_VIEW","DASHBOARD","PAGE_LAYOUT"]}""")));
    }

    @Test
    void emptyCollectionsArrayIsOmittedLikeTheCli() {
        wm.stubFor(post(urlEqualTo("/api/packages/export"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        tool.export(Map.of("collections", List.of()));

        wm.verify(postRequestedFor(urlEqualTo("/api/packages/export")).withRequestBody(equalToJson("{}")));
    }

    @Test
    void nonArrayCollectionsIsRejectedWithoutCallingGateway() {
        CallToolResult result = tool.export(Map.of("collections", "deals,accounts"));

        assertThat(result.isError()).isTrue();
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void gatewayErrorIsSurfacedAsToolError() {
        wm.stubFor(post(urlEqualTo("/api/packages/export"))
                .willReturn(aResponse().withStatus(403)
                        .withBody("{\"errors\":[{\"status\":\"403\",\"detail\":\"MANAGE_PACKAGES permission required\"}]}")));

        CallToolResult result = tool.export(Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("HTTP 403");
        assertThat(result.structuredContent()).isEqualTo(Map.of("status", 403,
                "errors", List.of(Map.of("status", "403", "detail", "MANAGE_PACKAGES permission required"))));
    }
}
