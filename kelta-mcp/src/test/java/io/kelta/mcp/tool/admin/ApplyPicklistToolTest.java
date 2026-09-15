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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ApplyPicklistToolTest {

    private static final String PICKLIST_ID = "p1";
    private static final String OPEN_VALUE_ID = "v-open";
    private static final String CLOSED_VALUE_ID = "v-closed";
    private static final String STALE_VALUE_ID = "v-stale";

    private WireMockServer wm;
    private ApplyPicklistTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyPicklistTool(client);
        RequestPatHolder.set("klt_apply_picklist_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.apply(args);
    }

    @Test
    void rejectsWithoutValues() {
        CallToolResult result = call(Map.of("name", "Stage"));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsValueEntryMissingLabel() {
        CallToolResult result = call(Map.of(
                "name", "Stage", "values", List.of(Map.of("value", "OPEN"))));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    /**
     * Writes label/color/sortOrder (from list position) for each value, and with
     * prune:true deactivates an existing active value absent from the input list.
     */
    @Test
    void writesLabelColorAndPositionalSortOrderAndPrunesAbsentValues() {
        wm.stubFor(get(urlEqualTo("/api/global-picklists?filter[name][eq]=Stage&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(post(urlEqualTo("/api/global-picklists"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + PICKLIST_ID + "\"}}")));

        wm.stubFor(get(urlEqualTo("/api/picklist-values?filter[picklistSourceType][eq]=GLOBAL"
                + "&filter[picklistSourceId][eq]=" + PICKLIST_ID + "&filter[value][eq]=OPEN&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(get(urlEqualTo("/api/picklist-values?filter[picklistSourceType][eq]=GLOBAL"
                + "&filter[picklistSourceId][eq]=" + PICKLIST_ID + "&filter[value][eq]=CLOSED&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .inScenario("create values")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + OPEN_VALUE_ID + "\"}}"))
                .willSetStateTo("open created"));
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .inScenario("create values")
                .whenScenarioStateIs("open created")
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + CLOSED_VALUE_ID + "\"}}")));

        wm.stubFor(get(urlEqualTo("/api/picklist-values?filter[picklistSourceType][eq]=GLOBAL"
                + "&filter[picklistSourceId][eq]=" + PICKLIST_ID + "&filter[isActive][eq]=true&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":["
                                + "{\"id\":\"" + OPEN_VALUE_ID + "\",\"attributes\":{\"value\":\"OPEN\"}},"
                                + "{\"id\":\"" + CLOSED_VALUE_ID + "\",\"attributes\":{\"value\":\"CLOSED\"}},"
                                + "{\"id\":\"" + STALE_VALUE_ID + "\",\"attributes\":{\"value\":\"ARCHIVED\"}}"
                                + "]}")));
        wm.stubFor(patch(urlEqualTo("/api/picklist-values/" + STALE_VALUE_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{}}")));

        CallToolResult result = call(Map.of(
                "name", "Stage",
                "values", List.of(
                        Map.of("value", "OPEN", "label", "Open", "color", "#22C55E"),
                        Map.of("value", "CLOSED", "label", "Closed", "color", "#EF4444")),
                "prune", true));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"created\"").contains("\"pruned\":[\"ARCHIVED\"]");

        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("OPEN")))
                .withRequestBody(matchingJsonPath("$.data.attributes.label", equalTo("Open")))
                .withRequestBody(matchingJsonPath("$.data.attributes.color", equalTo("#22C55E")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("0"))));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("CLOSED")))
                .withRequestBody(matchingJsonPath("$.data.attributes.label", equalTo("Closed")))
                .withRequestBody(matchingJsonPath("$.data.attributes.color", equalTo("#EF4444")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("1"))));
        wm.verify(1, WireMock.patchRequestedFor(urlEqualTo("/api/picklist-values/" + STALE_VALUE_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.isActive", equalTo("false"))));
    }

    @Test
    void leavesAbsentValueActiveWhenPruneOmitted() {
        wm.stubFor(get(urlEqualTo("/api/global-picklists?filter[name][eq]=Stage&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + PICKLIST_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/global-picklists/" + PICKLIST_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + PICKLIST_ID + "\",\"attributes\":{\"name\":\"Stage\"}}}")));

        wm.stubFor(get(urlEqualTo("/api/picklist-values?filter[picklistSourceType][eq]=GLOBAL"
                + "&filter[picklistSourceId][eq]=" + PICKLIST_ID + "&filter[value][eq]=OPEN&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + OPEN_VALUE_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/picklist-values/" + OPEN_VALUE_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + OPEN_VALUE_ID + "\",\"attributes\":{"
                                + "\"value\":\"OPEN\",\"label\":\"Open\",\"sortOrder\":0}}}")));

        CallToolResult result = call(Map.of(
                "name", "Stage",
                "values", List.of(Map.of("value", "OPEN", "label", "Open"))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        // No prune flag — no lookup of active values, so nothing can be deactivated.
        wm.verify(0, WireMock.getRequestedFor(urlEqualTo(
                "/api/picklist-values?filter[picklistSourceType][eq]=GLOBAL"
                        + "&filter[picklistSourceId][eq]=" + PICKLIST_ID + "&filter[isActive][eq]=true&page[size]=200")));
        wm.verify(0, WireMock.patchRequestedFor(WireMock.urlMatching("/api/picklist-values/.*")));
    }
}
