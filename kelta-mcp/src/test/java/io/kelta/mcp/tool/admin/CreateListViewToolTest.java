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

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * List views are records of the {@code list-views} system collection with a
 * {@code columns} JSON array and a {@code filters} array that must ALWAYS be
 * sent — the declared default is a string {@code "[]"} that fails the field's
 * own JSON type validation. (The tool previously posted to a non-existent
 * {@code /api/listViews} path with pass-through attribute names.)
 */
class CreateListViewToolTest {

    private static final String COLLECTION_ID = "11111111-1111-1111-1111-111111111111";

    private WireMockServer wm;
    private CreateListViewTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new CreateListViewTool(client);
        RequestPatHolder.set("klt_listview_test");
        wm.stubFor(get(urlEqualTo("/api/collections?filter[name][eq]=projects"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"type\":\"collections\",\"id\":\"" + COLLECTION_ID + "\"}]}")));
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void postsColumnsAndAlwaysSendsFilters() {
        wm.stubFor(post(urlEqualTo("/api/list-views"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"lv1\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_listview", Map.of(
                        "collectionName", "projects",
                        "name", "All projects",
                        "displayedFields", "name, owner ,stage",
                        "sort", "-createdAt"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/list-views"))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("list-views")))
                .withRequestBody(matchingJsonPath("$.data.attributes.collectionId", equalTo(COLLECTION_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.columns[0]", equalTo("name")))
                .withRequestBody(matchingJsonPath("$.data.attributes.columns[1]", equalTo("owner")))
                .withRequestBody(matchingJsonPath("$.data.attributes.columns[2]", equalTo("stage")))
                // filters must ALWAYS be present, even when no filter was given
                // (empty JSON arrays don't match a JsonPath presence check)
                .withRequestBody(WireMock.containing("\"filters\":[]"))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortField", equalTo("createdAt")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortDirection", equalTo("DESC"))));
    }

    @Test
    void mapsFilterObjectToFiltersArray() {
        wm.stubFor(post(urlEqualTo("/api/list-views"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"lv1\"}}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_listview", Map.of(
                        "collectionName", "projects",
                        "name", "Open",
                        "displayedFields", "name",
                        "filter", Map.of("status", Map.of("EQ", "OPEN"))), null));

        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/list-views"))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].field", equalTo("status")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].operator", equalTo("EQ")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].value", equalTo("OPEN"))));
    }

    /**
     * The published-board case (V196): the CLI's
     * `--view-type KANBAN --lane-field status --card-fields title` must land the
     * same row as this call, so both write `viewType` + a `typeConfig.kanban`
     * object — not a JSON string, which the end-user list would have to re-parse.
     */
    @Test
    void postsViewTypeAndTypeConfigForAPublishedKanban() {
        wm.stubFor(post(urlEqualTo("/api/list-views"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"lv1\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_listview", Map.of(
                        "collectionName", "projects",
                        "name", "The board",
                        "displayedFields", "title",
                        "visibility", "PUBLIC",
                        "viewType", "kanban",
                        "typeConfig", Map.of("kanban", Map.of(
                                "laneField", "status",
                                "cardFields", List.of("title")))), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/list-views"))
                // lowercase in, canonical uppercase out — the column's CHECK only
                // accepts the uppercase form
                .withRequestBody(matchingJsonPath("$.data.attributes.viewType", equalTo("KANBAN")))
                .withRequestBody(matchingJsonPath("$.data.attributes.visibility", equalTo("PUBLIC")))
                .withRequestBody(matchingJsonPath(
                        "$.data.attributes.typeConfig.kanban.laneField", equalTo("status")))
                .withRequestBody(matchingJsonPath(
                        "$.data.attributes.typeConfig.kanban.cardFields[0]", equalTo("title"))));
    }

    @Test
    void omitsRendererAttributesWhenNotAsked() {
        wm.stubFor(post(urlEqualTo("/api/list-views"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"lv1\"}}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_listview", Map.of(
                        "collectionName", "projects",
                        "name", "Plain",
                        "displayedFields", "name"), null));

        // Not sent at all rather than sent as TABLE: the column default owns that,
        // and a create that says nothing about the renderer must keep saying nothing.
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/list-views"))
                .withRequestBody(WireMock.notMatching("(?s).*\"viewType\".*"))
                .withRequestBody(WireMock.notMatching("(?s).*\"typeConfig\".*")));
    }

    /**
     * KLT-212: rowLimit must reach the worker so its own BeforeSave hook can
     * validate it against the fixed page-size grammar {10,25,50,100}, and an
     * {@code in} filter must forward with its CSV/array value untouched.
     */
    @Test
    void postsRowLimitAndInFilter() {
        wm.stubFor(post(urlEqualTo("/api/list-views"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"lv1\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_listview", Map.of(
                        "collectionName", "projects",
                        "name", "By status",
                        "displayedFields", "name",
                        "rowLimit", 25,
                        "filter", Map.of("status", Map.of("IN", "a,b"))), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/list-views"))
                .withRequestBody(matchingJsonPath("$.data.attributes.rowLimit", equalTo("25")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].field", equalTo("status")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].operator", equalTo("IN")))
                .withRequestBody(matchingJsonPath("$.data.attributes.filters[0].value", equalTo("a,b"))));
    }

    @Test
    void failsWhenCollectionUnknown() {
        wm.stubFor(get(urlEqualTo("/api/collections?filter[name][eq]=nope"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_listview", Map.of(
                        "collectionName", "nope",
                        "name", "x",
                        "displayedFields", "a"), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }
}
