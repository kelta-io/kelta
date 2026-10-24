package io.kelta.mcp.tool.user;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class SearchToolTest {

    private WireMockServer wm;
    private SearchTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new SearchTool(client);
        RequestPatHolder.set("klt_search_test");
        wm.stubFor(get(urlPathEqualTo("/api/_search"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("search", args, null));
    }

    @Test
    void getsSearchWithQueryAndLimit() {
        CallToolResult result = call(Map.of("query", "acme corp", "limit", 5));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/api/_search"))
                .withQueryParam("q", equalTo("acme corp"))
                .withQueryParam("limit", equalTo("5")));
    }

    @Test
    void omitsLimitWhenNotSupplied() {
        call(Map.of("query", "acme"));

        wm.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/api/_search"))
                .withQueryParam("q", equalTo("acme"))
                .withQueryParam("limit", absent()));
    }

    @Test
    void requiresQuery() {
        CallToolResult result = call(Map.of("limit", 5));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
