package io.kelta.mcp.tool.user;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticSearchToolTest {

    private WireMockServer wm;
    private SemanticSearchTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new SemanticSearchTool(client);
        RequestPatHolder.set("klt_semsearch");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void rejectsMissingCollectionOrQuery() {
        assertThat(tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("semantic_search", Map.of("query", "hi"), null)).isError())
                .isEqualTo(Boolean.TRUE);
        assertThat(tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("semantic_search", Map.of("collection", "docs"), null)).isError())
                .isEqualTo(Boolean.TRUE);
        assertThat(tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("semantic_search",
                        Map.of("collection", "docs", "query", "  "), null)).isError())
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void postsQueryToCollectionSemanticSearch() {
        wm.stubFor(post(urlEqualTo("/api/docs/semantic-search"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[],\"meta\":{\"count\":0}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("semantic_search",
                        Map.of("collection", "docs", "query", "annual report", "limit", 5), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/docs/semantic-search"))
                .withHeader("Authorization", equalTo("Bearer klt_semsearch"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("annual report")))
                .withRequestBody(matchingJsonPath("$.limit", equalTo("5"))));
    }

    @Test
    void declaresAReadOnlyTool() {
        var tool = this.tool.toSpecification().tool();
        assertThat(tool.name()).isEqualTo("semantic_search");
        assertThat(tool.inputSchema()).extractingByKey("required")
                .asInstanceOf(InstanceOfAssertFactories.LIST).contains("collection", "query");
    }
}
