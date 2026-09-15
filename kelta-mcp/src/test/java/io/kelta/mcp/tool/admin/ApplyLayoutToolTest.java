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

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool wraps the worker's whole-tree endpoint (KLT-214) with exactly one gateway
 * call per apply — no client-side name/id lookups, since the endpoint resolves those
 * server-side. The only client-side transform is defaulting an omitted field {@code
 * column} to a 0-based round-robin within its section.
 */
class ApplyLayoutToolTest {

    private WireMockServer wm;
    private ApplyLayoutTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyLayoutTool(client);
        RequestPatHolder.set("klt_apply_layout_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("apply_layout", args, null));
    }

    @Test
    void rejectsWithoutSections() {
        CallToolResult result = call(Map.of("collectionName", "projects", "name", "Main"));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsWithoutLayoutIdOrCollectionAndName() {
        CallToolResult result = call(Map.of("collectionName", "projects", "sections", List.of()));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void appliesByCollectionAndNameWhenNoLayoutId() {
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"layoutId\":\"L1\",\"collection\":\"projects\",\"name\":\"Main\","
                        + "\"created\":1,\"updated\":0,\"deleted\":0,\"unchanged\":0}")));

        CallToolResult result = call(Map.of(
                "collectionName", "projects",
                "name", "Main",
                "sections", List.of(Map.of("heading", "Overview",
                        "fields", List.of(Map.of("name", "name"))))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(1, WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                .withHeader("Authorization", equalTo("Bearer klt_apply_layout_test")));
    }

    @Test
    void appliesByLayoutIdWhenGiven() {
        wm.stubFor(put(urlEqualTo("/api/page-layouts/L1/tree"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"layoutId\":\"L1\",\"created\":0,\"updated\":1,\"deleted\":0,\"unchanged\":0}")));

        CallToolResult result = call(Map.of(
                "layoutId", "L1",
                "sections", List.of(Map.of("heading", "Overview",
                        "fields", List.of(Map.of("name", "name"))))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(0, WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/Main/tree")));
        wm.verify(1, WireMock.putRequestedFor(urlEqualTo("/api/page-layouts/L1/tree")));
    }

    @Test
    void placesFieldsInZeroBasedColumnsWhenColumnOmitted() {
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                .willReturn(aResponse().withStatus(200).withBody("{\"layoutId\":\"L1\"}")));

        Map<String, Object> section = Map.of(
                "heading", "Overview",
                "columns", 2,
                "fields", List.of(
                        Map.of("name", "name"),
                        Map.of("name", "owner"),
                        Map.of("name", "stage"),
                        Map.of("name", "notes")));

        CallToolResult result = call(Map.of(
                "collectionName", "projects", "name", "Main", "sections", List.of(section)));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        for (int i = 0; i < 4; i++) {
            wm.verify(WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                    .withRequestBody(matchingJsonPath(
                            "$.sections[0].fields[" + i + "].column", equalTo(String.valueOf(i % 2)))));
        }
    }

    @Test
    void leavesExplicitColumnAlone() {
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                .willReturn(aResponse().withStatus(200).withBody("{\"layoutId\":\"L1\"}")));

        Map<String, Object> section = Map.of(
                "heading", "Overview",
                "columns", 2,
                "fields", List.of(Map.of("name", "name", "column", 1)));

        call(Map.of("collectionName", "projects", "name", "Main", "sections", List.of(section)));

        wm.verify(WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                .withRequestBody(matchingJsonPath("$.sections[0].fields[0].column", equalTo("1"))));
    }

    @Test
    void applyingTwiceMakesAtMostTwoGatewayCallsAndSecondReportsNoChange() {
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                .inScenario("idempotent apply")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"layoutId\":\"L1\",\"created\":1,\"updated\":0,\"deleted\":0,\"unchanged\":0}"))
                .willSetStateTo("applied once"));
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/Main/tree"))
                .inScenario("idempotent apply")
                .whenScenarioStateIs("applied once")
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"layoutId\":\"L1\",\"created\":0,\"updated\":0,\"deleted\":0,\"unchanged\":1}")));

        Map<String, Object> args = Map.of(
                "collectionName", "projects",
                "name", "Main",
                "sections", List.of(Map.of("heading", "Overview",
                        "fields", List.of(Map.of("name", "name")))));

        CallToolResult first = call(args);
        CallToolResult second = call(args);

        assertThat(first.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(second.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((TextContent) second.content().get(0)).text()).contains("\"unchanged\":1");
        // One gateway call per apply — no pre-lookup, so two applies never exceed two calls total.
        wm.verify(2, WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/Main/tree")));
    }
}
