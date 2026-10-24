package io.kelta.mcp.tool.user;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ListCollectionsToolTest {

    private WireMockServer wm;
    private ListCollectionsTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ListCollectionsTool(client);
        RequestPatHolder.set("klt_list_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void toolHasExpectedNameAndRequiresNoArgs() {
        SyncToolSpecification spec = tool.toSpecification();
        assertThat(spec.tool().name()).isEqualTo("list_collections");
        assertThat(spec.tool().inputSchema()).extractingByKey("required")
                .asInstanceOf(InstanceOfAssertFactories.LIST).isEmpty();
    }

    @Test
    void defaultIncludesAllCollections() {
        wm.stubFor(get(urlEqualTo("/api/collections"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"c1\"}]}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("list_collections", Map.of(), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((TextContent) result.content().get(0)).text()).contains("c1");
        wm.verify(WireMock.getRequestedFor(urlEqualTo("/api/collections"))
                .withHeader("Authorization", equalTo("Bearer klt_list_test")));
    }

    @Test
    void includeSystemFalseAddsFilter() {
        wm.stubFor(get(urlEqualTo("/api/collections?filter[systemCollection][EQ]=false"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        tool.toSpecification().callHandler().apply(null, new CallToolRequest(
                "list_collections", Map.of("includeSystem", false), null));

        wm.verify(WireMock.getRequestedFor(urlEqualTo("/api/collections?filter[systemCollection][EQ]=false")));
    }

    @Test
    void gateway500BecomesIsErrorResult() {
        wm.stubFor(get(urlEqualTo("/api/collections"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("list_collections", Map.of(), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((TextContent) result.content().get(0)).text()).contains("500");
    }
}
