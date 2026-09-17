package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code apply_menu} is keyed on {@code name} for the menu and locally resolved {@code
 * (menu, parent, label)} for each item (see the class-level note on {@link ApplyMenuTool}
 * for why item resolution can't reuse {@link AdminLookups#upsert}'s remote filter directly).
 */
class ApplyMenuToolTest {

    private static final String MENU_ID = "M1";
    private static final String GROUP_ID = "G1";
    private static final String CHILD_ID = "C1";

    private WireMockServer wm;
    private ApplyMenuTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyMenuTool(client);
        RequestPatHolder.set("klt_apply_menu_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.apply(args);
    }

    private static Map<String, Object> groupAndChildItems() {
        return Map.of(
                "name", "Main",
                "items", List.of(Map.of(
                        "label", "Reports",
                        "children", List.of(Map.of("label", "Sales", "path", "/reports/rep1")))));
    }

    @Test
    void rejectsWithoutName() {
        CallToolResult result = call(Map.of("items", List.of()));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsWithoutItems() {
        CallToolResult result = call(Map.of("name", "Main"));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void descriptionIncludesWorkedExampleWithGroupAndChild() {
        String description = tool.toSpecification().tool().description();
        assertThat(description).contains("\"children\"").contains("\"label\":\"Sales\"");
    }

    /**
     * A group (an item with children and no path) is created in ui-menus/ui-menu-items, and
     * the child's parentId resolves to the group's freshly-created id.
     */
    @Test
    void createsGroupAndChildWithResolvedParentId() {
        wm.stubFor(get(urlEqualTo("/api/ui-menus?filter[name][eq]=Main&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(post(urlEqualTo("/api/ui-menus"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\"}}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menu-items?filter[menuId][eq]=" + MENU_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        wm.stubFor(post(urlEqualTo("/api/ui-menu-items"))
                .withRequestBody(matchingJsonPath("$.data.attributes.label", equalTo("Reports")))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + GROUP_ID + "\"}}")));
        wm.stubFor(post(urlEqualTo("/api/ui-menu-items"))
                .withRequestBody(matchingJsonPath("$.data.attributes.label", equalTo("Sales")))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + CHILD_ID + "\"}}")));

        CallToolResult result = call(groupAndChildItems());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"created\"").contains("\"id\":\"" + GROUP_ID + "\"")
                .contains("\"id\":\"" + CHILD_ID + "\"");

        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/ui-menu-items"))
                .withRequestBody(matchingJsonPath("$.data.attributes.label", equalTo("Reports")))
                .withRequestBody(matchingJsonPath("$.data.attributes.menuId", equalTo(MENU_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.displayOrder", equalTo("0"))));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/ui-menu-items"))
                .withRequestBody(matchingJsonPath("$.data.attributes.label", equalTo("Sales")))
                .withRequestBody(matchingJsonPath("$.data.attributes.parentId", equalTo(GROUP_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.path", equalTo("/reports/rep1"))));
    }

    /** Re-applying the identical menu reports every item — and the menu itself — as unchanged. */
    @Test
    void reappliesIdenticalMenuAsUnchanged() {
        wm.stubFor(get(urlEqualTo("/api/ui-menus?filter[name][eq]=Main&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + MENU_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menus/" + MENU_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\",\"attributes\":{\"name\":\"Main\"}}}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menu-items?filter[menuId][eq]=" + MENU_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":["
                                + "{\"id\":\"" + GROUP_ID + "\",\"attributes\":{\"label\":\"Reports\",\"displayOrder\":0},"
                                + "\"relationships\":{\"menuId\":{\"data\":{\"id\":\"" + MENU_ID + "\"}}}},"
                                + "{\"id\":\"" + CHILD_ID + "\",\"attributes\":{\"label\":\"Sales\","
                                + "\"path\":\"/reports/rep1\",\"displayOrder\":0},"
                                + "\"relationships\":{\"menuId\":{\"data\":{\"id\":\"" + MENU_ID + "\"}},"
                                + "\"parentId\":{\"data\":{\"id\":\"" + GROUP_ID + "\"}}}}"
                                + "]}")));

        CallToolResult result = call(groupAndChildItems());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"unchanged\",\"id\":\"" + MENU_ID + "\"");
        assertThat(text).contains("\"label\":\"Reports\",\"action\":\"unchanged\"");
        assertThat(text).contains("\"label\":\"Sales\",\"action\":\"unchanged\"");

        wm.verify(0, WireMock.postRequestedFor(WireMock.urlMatching("/api/ui-menu-items.*")));
        wm.verify(0, WireMock.patchRequestedFor(WireMock.urlMatching("/api/ui-menu-items/.*")));
    }

    /**
     * A tree of three top-level items where only the middle one's {@code path} differs from what's
     * stored reports {@code updated} with {@code changed:["path"]} for that item only — the other
     * items and the menu itself report {@code unchanged}.
     */
    @Test
    void reportsUpdatedWithChangedPathForOnlyTheItemWhosePathDiffers() {
        String itemA = "IA";
        String itemB = "IB";
        String itemC = "IC";

        wm.stubFor(get(urlEqualTo("/api/ui-menus?filter[name][eq]=Main&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + MENU_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menus/" + MENU_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\",\"attributes\":{\"name\":\"Main\"}}}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menu-items?filter[menuId][eq]=" + MENU_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":["
                                + "{\"id\":\"" + itemA + "\",\"attributes\":{\"label\":\"A\","
                                + "\"path\":\"/resources/a\",\"displayOrder\":0}},"
                                + "{\"id\":\"" + itemB + "\",\"attributes\":{\"label\":\"B\","
                                + "\"path\":\"/resources/old\",\"displayOrder\":1}},"
                                + "{\"id\":\"" + itemC + "\",\"attributes\":{\"label\":\"C\","
                                + "\"path\":\"/resources/c\",\"displayOrder\":2}}"
                                + "]}")));
        wm.stubFor(WireMock.patch(urlEqualTo("/api/ui-menu-items/" + itemB))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + itemB + "\"}}")));

        CallToolResult result = call(Map.of(
                "name", "Main",
                "items", List.of(
                        Map.of("label", "A", "path", "/resources/a"),
                        Map.of("label", "B", "path", "/resources/new"),
                        Map.of("label", "C", "path", "/resources/c"))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"unchanged\",\"id\":\"" + MENU_ID + "\"");
        assertThat(text).contains("\"label\":\"A\",\"action\":\"unchanged\"");
        assertThat(text).contains(
                "\"label\":\"B\",\"action\":\"updated\",\"id\":\"" + itemB + "\",\"changed\":[\"path\"]");
        assertThat(text).contains("\"label\":\"C\",\"action\":\"unchanged\"");

        wm.verify(1, WireMock.patchRequestedFor(urlEqualTo("/api/ui-menu-items/" + itemB)));
        wm.verify(0, WireMock.patchRequestedFor(urlEqualTo("/api/ui-menu-items/" + itemA)));
        wm.verify(0, WireMock.patchRequestedFor(urlEqualTo("/api/ui-menu-items/" + itemC)));
        wm.verify(0, WireMock.patchRequestedFor(urlEqualTo("/api/ui-menus/" + MENU_ID)));
    }

    /**
     * The menu row itself diffs on {@code description}, {@code icon}, {@code isDefault} and
     * {@code active} the same way an item does — only the keys that actually differ from the
     * stored record are reported as {@code changed} and PATCHed.
     */
    @Test
    void reportsChangedMenuAttributesForDescriptionIconIsDefaultAndActive() {
        wm.stubFor(get(urlEqualTo("/api/ui-menus?filter[name][eq]=Main&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + MENU_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menus/" + MENU_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\",\"attributes\":{\"name\":\"Main\","
                                + "\"description\":\"old desc\",\"icon\":\"old-icon\","
                                + "\"isDefault\":false,\"active\":true}}}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menu-items?filter[menuId][eq]=" + MENU_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(WireMock.patch(urlEqualTo("/api/ui-menus/" + MENU_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\"}}")));

        CallToolResult result = call(Map.of(
                "name", "Main",
                "description", "new desc",
                "icon", "new-icon",
                "isDefault", true,
                "active", false,
                "items", List.of()));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"updated\",\"id\":\"" + MENU_ID + "\"")
                .contains("\"changed\":[\"description\",\"icon\",\"isDefault\",\"active\"]");

        wm.verify(1, WireMock.patchRequestedFor(urlEqualTo("/api/ui-menus/" + MENU_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.description", equalTo("new desc")))
                .withRequestBody(matchingJsonPath("$.data.attributes.icon", equalTo("new-icon")))
                .withRequestBody(matchingJsonPath("$.data.attributes.isDefault", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.data.attributes.active", equalTo("false"))));
    }

    /**
     * Applying the identical body twice in a row: the first call creates the menu and its item,
     * the second call — against the now-existing state — reports {@code unchanged} for both and
     * issues no PATCH (or further POST) at all.
     */
    @Test
    void secondIdenticalApplyIssuesNoWrites() {
        String itemId = "I1";
        Map<String, Object> body = Map.of(
                "name", "Main",
                "items", List.of(Map.of("label", "Tasks", "path", "/resources/tasks")));

        wm.stubFor(get(urlEqualTo("/api/ui-menus?filter[name][eq]=Main&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(post(urlEqualTo("/api/ui-menus"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\"}}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menu-items?filter[menuId][eq]=" + MENU_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(post(urlEqualTo("/api/ui-menu-items"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + itemId + "\"}}")));

        CallToolResult first = call(body);
        assertThat(((TextContent) first.content().get(0)).text()).contains("\"action\":\"created\"");

        wm.stubFor(get(urlEqualTo("/api/ui-menus?filter[name][eq]=Main&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + MENU_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menus/" + MENU_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\",\"attributes\":{\"name\":\"Main\"}}}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menu-items?filter[menuId][eq]=" + MENU_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + itemId + "\",\"attributes\":{\"label\":\"Tasks\","
                                + "\"path\":\"/resources/tasks\",\"displayOrder\":0}}]}")));

        CallToolResult second = call(body);
        String secondText = ((TextContent) second.content().get(0)).text();
        assertThat(secondText).contains("\"action\":\"unchanged\",\"id\":\"" + MENU_ID + "\"");
        assertThat(secondText).contains("\"label\":\"Tasks\",\"action\":\"unchanged\"");

        wm.verify(0, WireMock.patchRequestedFor(WireMock.urlMatching("/api/ui-menu-items/.*")));
        wm.verify(0, WireMock.patchRequestedFor(WireMock.urlMatching("/api/ui-menus/.*")));
        wm.verify(1, WireMock.postRequestedFor(urlEqualTo("/api/ui-menus")));
        wm.verify(1, WireMock.postRequestedFor(urlEqualTo("/api/ui-menu-items")));
    }

    /** Removing the child and re-applying with prune:true deletes the stale ui-menu-item row. */
    @Test
    void prunesRemovedChildWhenPruneTrue() {
        stubExistingGroupWithChild();

        CallToolResult result = call(Map.of(
                "name", "Main",
                "items", List.of(Map.of("label", "Reports")),
                "prune", true));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"pruned\":[\"Sales\"]");

        wm.verify(1, WireMock.deleteRequestedFor(urlEqualTo("/api/ui-menu-items/" + CHILD_ID)));
    }

    /** Removing the child and re-applying WITHOUT prune reports it as stale but does not delete it. */
    @Test
    void reportsRemovedChildAsStaleWithoutPrune() {
        stubExistingGroupWithChild();

        CallToolResult result = call(Map.of(
                "name", "Main",
                "items", List.of(Map.of("label", "Reports"))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"stale\":[\"Sales\"]");

        wm.verify(0, WireMock.deleteRequestedFor(WireMock.urlMatching("/api/ui-menu-items/.*")));
    }

    private void stubExistingGroupWithChild() {
        wm.stubFor(get(urlEqualTo("/api/ui-menus?filter[name][eq]=Main&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + MENU_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menus/" + MENU_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + MENU_ID + "\",\"attributes\":{\"name\":\"Main\"}}}")));
        wm.stubFor(get(urlEqualTo("/api/ui-menu-items?filter[menuId][eq]=" + MENU_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":["
                                + "{\"id\":\"" + GROUP_ID + "\",\"attributes\":{\"label\":\"Reports\",\"displayOrder\":0},"
                                + "\"relationships\":{\"menuId\":{\"data\":{\"id\":\"" + MENU_ID + "\"}}}},"
                                + "{\"id\":\"" + CHILD_ID + "\",\"attributes\":{\"label\":\"Sales\","
                                + "\"path\":\"/reports/rep1\",\"displayOrder\":0},"
                                + "\"relationships\":{\"menuId\":{\"data\":{\"id\":\"" + MENU_ID + "\"}},"
                                + "\"parentId\":{\"data\":{\"id\":\"" + GROUP_ID + "\"}}}}"
                                + "]}")));
        wm.stubFor(delete(urlEqualTo("/api/ui-menu-items/" + CHILD_ID))
                .willReturn(aResponse().withStatus(204)));
    }

    /**
     * A path that doesn't match one of the grammar shapes from navTabs.ts fails with a
     * structured error naming the grammar, and nothing is written — no gateway call at all.
     */
    @Test
    void rejectsInvalidPathWithStructuredError() {
        CallToolResult result = call(Map.of(
                "name", "Main",
                "items", List.of(Map.of("label", "External", "path", "https://example.com"))));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("https://example.com").contains("grammar");
        assertThat(result.structuredContent()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured.get("status")).isEqualTo(400);
        @SuppressWarnings("unchecked")
        List<Object> errors = (List<Object>) structured.get("errors");
        assertThat(errors).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstError = (Map<String, Object>) errors.get(0);
        assertThat(firstError.get("code")).isEqualTo("INVALID_PATH");
        assertThat(firstError.get("detail").toString()).contains("navTabs.ts");

        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /** Same rejection when the invalid path is on a child rather than a top-level item. */
    @Test
    void rejectsInvalidPathOnChildWithStructuredError() {
        CallToolResult result = call(Map.of(
                "name", "Main",
                "items", List.of(Map.of(
                        "label", "Reports",
                        "children", List.of(Map.of("label", "Bad", "path", "https://example.com"))))));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isNotNull();
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
