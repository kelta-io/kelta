package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.common.McpTransportContext;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * dashboard_data resolves a dashboard name to its id ({@code filter[name][eq]}, as {@code kelta
 * dashboards data} does), passes an id through, and posts the caller's body (or {@code {}}) to
 * {@code /api/dashboards/{id}/data}.
 */
class DashboardDataToolTest {

    private static final String DASHBOARD_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private WireMockServer wm;
    private DashboardDataTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new DashboardDataTool(client);
        RequestPatHolder.set("klt_dashboard_data_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private CallToolResult invoke(Map<String, Object> args) {
        return tool.toSpecification().callHandler()
                .apply(McpTransportContext.EMPTY, new CallToolRequest("dashboard_data", args, null));
    }

    @Test
    void resolvesNameAndPostsBody() {
        String response = "{\"components\":[{\"value\":42}]}";
        wm.stubFor(get(urlEqualTo("/api/dashboards?filter[name][eq]=Sales+Overview&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"" + DASHBOARD_ID + "\"}]}")));
        wm.stubFor(post(urlEqualTo("/api/dashboards/" + DASHBOARD_ID + "/data"))
                .willReturn(aResponse().withStatus(200).withBody(response)));

        CallToolResult result = invoke(Map.of("dashboard", "Sales Overview", "body", Map.of("timeRange", "LAST_30_DAYS")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo(response);
        wm.verify(postRequestedFor(urlEqualTo("/api/dashboards/" + DASHBOARD_ID + "/data"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"timeRange\":\"LAST_30_DAYS\"}")));
    }

    @Test
    void idPassesThroughAndMissingBodySendsEmptyObject() {
        wm.stubFor(post(urlEqualTo("/api/dashboards/" + DASHBOARD_ID + "/data"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        CallToolResult result = invoke(Map.of("dashboard", DASHBOARD_ID));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(postRequestedFor(urlEqualTo("/api/dashboards/" + DASHBOARD_ID + "/data"))
                .withRequestBody(equalToJson("{}")));
        wm.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void unknownDashboardNameIsAToolErrorAndPostsNothing() {
        wm.stubFor(get(urlEqualTo("/api/dashboards?filter[name][eq]=Nope&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        CallToolResult result = invoke(Map.of("dashboard", "Nope"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Dashboard \"Nope\" not found");
        wm.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void gatewayErrorIsSurfacedAsToolError() {
        wm.stubFor(post(urlEqualTo("/api/dashboards/" + DASHBOARD_ID + "/data"))
                .willReturn(aResponse().withStatus(404)
                        .withBody("{\"errors\":[{\"status\":\"404\",\"detail\":\"Dashboard not found\"}]}")));

        CallToolResult result = invoke(Map.of("dashboard", DASHBOARD_ID));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("HTTP 404");
    }

    @Test
    void missingDashboardIsRejectedWithoutCallingGateway() {
        assertThat(invoke(Map.of()).isError()).isTrue();
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
