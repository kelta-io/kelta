package io.kelta.mcp.tool.user;

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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class DescribeApiToolTest {

    private WireMockServer wm;
    private DescribeApiTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new DescribeApiTool(client);
        RequestPatHolder.set("klt_describe_api_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call() {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("describe_api", Map.of(), null));
    }

    @Test
    void getsTheTenantOpenApiSpecWithoutBodyOrQuery() {
        String spec = "{\"openapi\":\"3.0.1\",\"paths\":{\"/accounts\":{}}}";
        wm.stubFor(get(urlEqualTo("/api/docs/openapi.json"))
                .willReturn(aResponse().withStatus(200).withBody(spec)));

        CallToolResult result = call();

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(spec);
        wm.verify(1, WireMock.getRequestedFor(urlEqualTo("/api/docs/openapi.json"))
                .withHeader("Authorization", WireMock.equalTo("Bearer klt_describe_api_test")));
    }

    @Test
    void mapsGatewayErrors() {
        wm.stubFor(get(urlEqualTo("/api/docs/openapi.json"))
                .willReturn(aResponse().withStatus(403).withBody(
                        "{\"errors\":[{\"status\":\"403\",\"code\":\"FORBIDDEN\"}]}")));

        CallToolResult result = call();

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) result.structuredContent()).get("status")).isEqualTo(403);
    }
}
