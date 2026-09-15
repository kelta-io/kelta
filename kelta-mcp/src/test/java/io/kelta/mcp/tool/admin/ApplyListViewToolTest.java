package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * apply_listview is keyed on (collectionId, name): create on no match, unchanged when a
 * fresh read matches the desired body exactly, updated (with the changed keys) otherwise —
 * verified here as a WireMock request sequence across three calls with the same collection
 * + name, matching the tool's idempotent-upsert contract.
 */
class ApplyListViewToolTest {

    private static final String COLLECTION_ID = "c1";
    private static final String VIEW_ID = "v1";

    private WireMockServer wm;
    private ApplyListViewTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyListViewTool(client);
        RequestPatHolder.set("klt_apply_listview_test");

        wm.stubFor(get(urlEqualTo("/api/collections?filter[name][eq]=projects"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + COLLECTION_ID + "\"}]}")));
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.apply(args);
    }

    private static Map<String, Object> baseArgs(Object rowLimit) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("collectionName", "projects");
        args.put("name", "Main");
        args.put("columns", List.of("name"));
        args.put("rowLimit", rowLimit);
        return args;
    }

    @Test
    void rejectsWithoutColumns() {
        CallToolResult result = call(Map.of("collectionName", "projects", "name", "Main"));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void firstCallCreatesSecondReportsUnchangedThirdReportsUpdated() {
        wm.stubFor(get(urlEqualTo(
                "/api/list-views?filter[collectionId][eq]=" + COLLECTION_ID + "&filter[name][eq]=Main&page[size]=1"))
                .inScenario("apply-listview")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}"))
                .willSetStateTo("exists"));
        wm.stubFor(get(urlEqualTo(
                "/api/list-views?filter[collectionId][eq]=" + COLLECTION_ID + "&filter[name][eq]=Main&page[size]=1"))
                .inScenario("apply-listview")
                .whenScenarioStateIs("exists")
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + VIEW_ID + "\"}]}")));

        wm.stubFor(post(urlEqualTo("/api/list-views"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + VIEW_ID + "\"}}")));

        // What a fresh GET of the created record would return — used to diff calls 2 and 3.
        wm.stubFor(get(urlEqualTo("/api/list-views/" + VIEW_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + VIEW_ID + "\",\"attributes\":{"
                                + "\"collectionId\":\"" + COLLECTION_ID + "\",\"name\":\"Main\","
                                + "\"columns\":[\"name\"],\"filters\":[],\"rowLimit\":50}}}")));

        wm.stubFor(patch(urlEqualTo("/api/list-views/" + VIEW_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + VIEW_ID + "\"}}")));

        CallToolResult first = call(baseArgs(50));
        assertThat(first.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(first)).contains("\"action\":\"created\"").contains("\"id\":\"" + VIEW_ID + "\"");

        CallToolResult second = call(baseArgs(50));
        assertThat(second.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(second)).contains("\"action\":\"unchanged\"").contains("\"id\":\"" + VIEW_ID + "\"");

        CallToolResult third = call(baseArgs(100));
        assertThat(third.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(third))
                .contains("\"action\":\"updated\"")
                .contains("\"changed\":[\"rowLimit\"]");

        wm.verify(1, WireMock.postRequestedFor(urlEqualTo("/api/list-views"))
                .withRequestBody(matchingJsonPath("$.data.attributes.rowLimit", WireMock.equalTo("50"))));
        // Only the changed key is in the PATCH body — columns/filters aren't re-sent.
        wm.verify(1, WireMock.patchRequestedFor(urlEqualTo("/api/list-views/" + VIEW_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.rowLimit", WireMock.equalTo("100")))
                .withRequestBody(matchingJsonPath("$.data.attributes", WireMock.equalToJson("{\"rowLimit\":100}"))));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
