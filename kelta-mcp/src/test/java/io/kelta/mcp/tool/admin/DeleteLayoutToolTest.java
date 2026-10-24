package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class DeleteLayoutToolTest {

    private static final String LAYOUT_ID = "22222222-2222-2222-2222-222222222222";

    private WireMockServer wm;
    private DeleteLayoutTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new DeleteLayoutTool(client);
        RequestPatHolder.set("klt_delete_layout_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("delete_layout", args, null));
    }

    @Test
    void deletesThePageLayoutById() {
        wm.stubFor(delete(urlEqualTo("/api/page-layouts/" + LAYOUT_ID))
                .willReturn(aResponse().withStatus(204)));

        CallToolResult result = call(Map.of("id", LAYOUT_ID));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((TextContent) result.content().get(0)).text())
                .isEqualTo("Deleted pageLayout " + LAYOUT_ID + " (HTTP 204)");
        wm.verify(1, WireMock.deleteRequestedFor(urlEqualTo("/api/page-layouts/" + LAYOUT_ID))
                .withHeader("Authorization", WireMock.equalTo("Bearer klt_delete_layout_test")));
    }

    @Test
    void surfacesGatewayErrorAsStructuredResult() {
        wm.stubFor(delete(urlEqualTo("/api/page-layouts/" + LAYOUT_ID))
                .willReturn(aResponse().withStatus(404).withBody(
                        "{\"errors\":[{\"status\":\"404\",\"code\":\"NOT_FOUND\"}]}")));

        CallToolResult result = call(Map.of("id", LAYOUT_ID));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.structuredContent()).get("status")).isEqualTo(404);
    }

    @Test
    void requiresId() {
        CallToolResult result = call(Map.of());

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
