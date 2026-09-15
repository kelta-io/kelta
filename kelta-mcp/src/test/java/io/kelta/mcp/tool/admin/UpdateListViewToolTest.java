package io.kelta.mcp.tool.admin;

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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class UpdateListViewToolTest {

    private static final String LV_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private WireMockServer wm;
    private UpdateListViewTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new UpdateListViewTool(client);
        RequestPatHolder.set("klt_update_listview");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void rejectsWithoutId() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview", Map.of(), null));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsWithNoUpdatableFields() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview",
                        Map.of("id", LV_ID), null));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void patchesNameAndColumns() {
        wm.stubFor(patch(urlEqualTo("/api/list-views/" + LV_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + LV_ID + "\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview", Map.of(
                        "id", LV_ID,
                        "name", "My Updated View",
                        "displayedFields", "name, status, owner"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/list-views/" + LV_ID))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("list-views")))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo(LV_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("My Updated View")))
                .withRequestBody(matchingJsonPath("$.data.attributes.columns[0]", equalTo("name")))
                .withRequestBody(matchingJsonPath("$.data.attributes.columns[1]", equalTo("status")))
                .withRequestBody(matchingJsonPath("$.data.attributes.columns[2]", equalTo("owner"))));
    }

    @Test
    void mapsFilterObjectToFiltersArray() {
        wm.stubFor(patch(urlEqualTo("/api/list-views/" + LV_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + LV_ID + "\"}}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview", Map.of(
                        "id", LV_ID,
                        "filter", Map.of("status", Map.of("EQ", "OPEN"))), null));

        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/list-views/" + LV_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].field", equalTo("status")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].operator", equalTo("EQ")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].value", equalTo("OPEN"))));
    }

    @Test
    void patchesViewTypeAndTypeConfig() {
        wm.stubFor(patch(urlEqualTo("/api/list-views/" + LV_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + LV_ID + "\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview", Map.of(
                        "id", LV_ID,
                        "viewType", "kanban",
                        "typeConfig", Map.of("kanban", Map.of("laneField", "status"))), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/list-views/" + LV_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.viewType", equalTo("KANBAN")))
                .withRequestBody(matchingJsonPath(
                        "$.data.attributes.typeConfig.kanban.laneField", equalTo("status"))));
    }

    /** An empty object clears the renderer settings, mirroring `filter: {}`. */
    @Test
    void anEmptyTypeConfigClearsIt() {
        wm.stubFor(patch(urlEqualTo("/api/list-views/" + LV_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + LV_ID + "\"}}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview", Map.of(
                        "id", LV_ID,
                        "typeConfig", Map.of()), null));

        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/list-views/" + LV_ID))
                .withRequestBody(WireMock.containing("\"typeConfig\":null")));
    }

    @Test
    void forwardsRowLimitAndInFilter() {
        wm.stubFor(patch(urlEqualTo("/api/list-views/" + LV_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + LV_ID + "\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview", Map.of(
                        "id", LV_ID,
                        "rowLimit", 25,
                        "filter", Map.of("status", Map.of("IN", "a,b"))), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/list-views/" + LV_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.rowLimit", equalTo("25")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].field", equalTo("status")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].operator", equalTo("IN")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].value", equalTo("a,b"))));
    }

    @Test
    void mapsSortFieldAndDirection() {
        wm.stubFor(patch(urlEqualTo("/api/list-views/" + LV_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + LV_ID + "\"}}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_listview", Map.of(
                        "id", LV_ID,
                        "sort", "-createdAt"), null));

        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/list-views/" + LV_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortField", equalTo("createdAt")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortDirection", equalTo("DESC"))));
    }
}
