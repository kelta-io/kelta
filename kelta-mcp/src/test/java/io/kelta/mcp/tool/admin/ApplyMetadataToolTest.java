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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * apply_metadata posts the package to {@code /api/packages/import} with the CLI's query options:
 * {@code dryRun=true} only when asked, {@code conflictMode} always (default {@code skip}).
 */
class ApplyMetadataToolTest {

    private WireMockServer wm;
    private ApplyMetadataTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyMetadataTool(client);
        RequestPatHolder.set("klt_apply_metadata_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static final String PACKAGE = "{\"name\":\"crm\",\"version\":\"1.0.0\",\"items\":[]}";

    @Test
    void postsPackageToImportWithDefaultConflictMode() {
        wm.stubFor(post(urlEqualTo("/api/packages/import?conflictMode=skip"))
                .willReturn(aResponse().withStatus(200).withBody("{\"created\":1,\"updated\":0,\"skipped\":0,\"failed\":0}")));

        CallToolResult result = tool.apply(Map.of("package", Map.of("name", "crm", "version", "1.0.0", "items", List.of())));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("\"created\":1");
        wm.verify(postRequestedFor(urlEqualTo("/api/packages/import?conflictMode=skip"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(PACKAGE)));
    }

    @Test
    void dryRunAndOverwriteAreSentAsQueryParams() {
        wm.stubFor(post(urlEqualTo("/api/packages/import?dryRun=true&conflictMode=overwrite"))
                .willReturn(aResponse().withStatus(200).withBody("{\"created\":0}")));

        CallToolResult result = tool.apply(Map.of("package", PACKAGE, "dryRun", true, "conflictMode", "overwrite"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(postRequestedFor(urlEqualTo("/api/packages/import?dryRun=true&conflictMode=overwrite"))
                .withRequestBody(equalToJson(PACKAGE)));
    }

    @Test
    void unknownConflictModeIsRejectedWithoutCallingGateway() {
        CallToolResult result = tool.apply(Map.of("package", PACKAGE, "conflictMode", "merge"));

        assertThat(result.isError()).isTrue();
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void gatewayErrorIsSurfacedAsToolError() {
        wm.stubFor(post(urlEqualTo("/api/packages/import?conflictMode=skip"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"Import failed: boom\"}")));

        CallToolResult result = tool.apply(Map.of("package", PACKAGE));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("HTTP 400").contains("Import failed");
    }
}
