package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
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
 * apply_dashboard is keyed on {@code name} for the dashboard (via {@link AdminLookups#upsert})
 * and locally resolved {@code title} for each component (via one {@link AdminLookups#list} of
 * the dashboard's existing components, the same shape {@link ApplyMenuTool} uses for items).
 * Every component is dry-run through {@code POST /api/dashboards/{id}/validate} before any
 * component create/update/delete — these tests stub that endpoint explicitly rather than
 * re-deriving {@code DashboardComponentValidator}'s own checks.
 */
class ApplyDashboardToolTest {

    private static final String DASHBOARD_ID = "D1";

    private WireMockServer wm;
    private ApplyDashboardTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyDashboardTool(client);
        RequestPatHolder.set("klt_apply_dashboard_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.apply(args);
    }

    private static Map<String, Object> component(String title, String type, int col, int row) {
        return Map.of("title", title, "componentType", type, "columnPosition", col, "rowPosition", row);
    }

    private static Map<String, Object> threeComponents() {
        return Map.of(
                "name", "SalesOverview",
                "components", List.of(
                        component("Total Deals", "metric", 1, 1),
                        component("Deals by Stage", "chart", 2, 1),
                        component("Recent Deals", "table", 1, 2)));
    }

    @Test
    void rejectsWithoutName() {
        CallToolResult result = call(Map.of("components", List.of()));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsWithoutComponents() {
        CallToolResult result = call(Map.of("name", "SalesOverview"));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void descriptionIncludesWorkedExampleWithMetricChartAndTable() {
        String description = tool.toSpecification().tool().description();
        assertThat(description)
                .contains("\"componentType\":\"metric\"")
                .contains("\"componentType\":\"chart\"")
                .contains("\"componentType\":\"table\"");
    }

    /** New dashboard, three new components — all created, sortOrder derived from array position. */
    @Test
    void createsDashboardWithThreeComponents() {
        stubNewDashboard();
        stubValidate(true, List.of());
        wm.stubFor(get(urlEqualTo("/api/dashboard-components?filter[dashboardId][eq]=" + DASHBOARD_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        stubCreateComponent("Total Deals", "C1");
        stubCreateComponent("Deals by Stage", "C2");
        stubCreateComponent("Recent Deals", "C3");

        CallToolResult result = call(threeComponents());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"created\",\"id\":\"" + DASHBOARD_ID + "\"");
        assertThat(text).contains("\"title\":\"Total Deals\",\"action\":\"created\",\"id\":\"C1\"");
        assertThat(text).contains("\"title\":\"Deals by Stage\",\"action\":\"created\",\"id\":\"C2\"");
        assertThat(text).contains("\"title\":\"Recent Deals\",\"action\":\"created\",\"id\":\"C3\"");

        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/dashboard-components"))
                .withRequestBody(matchingJsonPath("$.data.attributes.title", equalTo("Total Deals")))
                .withRequestBody(matchingJsonPath("$.data.attributes.dashboardId", equalTo(DASHBOARD_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("0"))));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/dashboard-components"))
                .withRequestBody(matchingJsonPath("$.data.attributes.title", equalTo("Deals by Stage")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("1"))));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/dashboard-components"))
                .withRequestBody(matchingJsonPath("$.data.attributes.title", equalTo("Recent Deals")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("2"))));

        for (ServeEvent event : wm.getAllServeEvents()) {
            if (event.getRequest().getUrl().equals("/api/dashboard-components")) {
                assertThat(event.getRequest().getBodyAsString()).doesNotContain("reportId");
            }
        }
    }

    /** Re-applying the identical dashboard reports the dashboard and every component as unchanged. */
    @Test
    void reappliesIdenticalDashboardAsUnchanged() {
        stubExistingDashboard();
        stubValidate(true, List.of());
        stubExistingComponents(
                Map.of("id", "C1", "title", "Total Deals", "componentType", "metric",
                        "columnPosition", 1, "rowPosition", 1, "columnSpan", 1, "rowSpan", 1,
                        "config", Map.of(), "sortOrder", 0),
                Map.of("id", "C2", "title", "Deals by Stage", "componentType", "chart",
                        "columnPosition", 2, "rowPosition", 1, "columnSpan", 1, "rowSpan", 1,
                        "config", Map.of(), "sortOrder", 1),
                Map.of("id", "C3", "title", "Recent Deals", "componentType", "table",
                        "columnPosition", 1, "rowPosition", 2, "columnSpan", 1, "rowSpan", 1,
                        "config", Map.of(), "sortOrder", 2));

        CallToolResult result = call(threeComponents());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"unchanged\",\"id\":\"" + DASHBOARD_ID + "\"");
        assertThat(text).contains("\"title\":\"Total Deals\",\"action\":\"unchanged\"");
        assertThat(text).contains("\"title\":\"Deals by Stage\",\"action\":\"unchanged\"");
        assertThat(text).contains("\"title\":\"Recent Deals\",\"action\":\"unchanged\"");

        wm.verify(0, WireMock.postRequestedFor(WireMock.urlMatching("/api/dashboard-components.*")));
        wm.verify(0, WireMock.patchRequestedFor(WireMock.urlMatching("/api/dashboard-components/.*")));
        wm.verify(0, WireMock.deleteRequestedFor(WireMock.urlMatching("/api/dashboard-components/.*")));
    }

    /** Changing one component's title creates a new component and, with prune:true, deletes the old row. */
    @Test
    void changingTitleCreatesNewAndPruneDeletesOld() {
        stubExistingDashboard();
        stubValidate(true, List.of());
        stubExistingComponents(
                Map.of("id", "C1", "title", "Total Deals", "componentType", "metric",
                        "columnPosition", 1, "rowPosition", 1, "columnSpan", 1, "rowSpan", 1,
                        "config", Map.of(), "sortOrder", 0),
                Map.of("id", "C2", "title", "Deals by Stage", "componentType", "chart",
                        "columnPosition", 2, "rowPosition", 1, "columnSpan", 1, "rowSpan", 1,
                        "config", Map.of(), "sortOrder", 1),
                Map.of("id", "C3", "title", "Recent Deals", "componentType", "table",
                        "columnPosition", 1, "rowPosition", 2, "columnSpan", 1, "rowSpan", 1,
                        "config", Map.of(), "sortOrder", 2));
        stubCreateComponent("Deals by Rep", "C4");
        wm.stubFor(delete(urlEqualTo("/api/dashboard-components/C2"))
                .willReturn(aResponse().withStatus(204)));

        CallToolResult result = call(Map.of(
                "name", "SalesOverview",
                "prune", true,
                "components", List.of(
                        component("Total Deals", "metric", 1, 1),
                        component("Deals by Rep", "chart", 2, 1),
                        component("Recent Deals", "table", 1, 2))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"title\":\"Deals by Rep\",\"action\":\"created\",\"id\":\"C4\"");
        assertThat(text).contains("\"pruned\":[\"Deals by Stage\"]");

        wm.verify(1, WireMock.deleteRequestedFor(urlEqualTo("/api/dashboard-components/C2")));
    }

    /**
     * A component with columnPosition:0 fails validation server-side (DashboardComponentValidator,
     * KLT-213) — the tool maps the returned field error onto /components/0/columnPosition and
     * writes no component at all (no list/create/update/delete call is even made).
     */
    @Test
    void rejectsInvalidColumnPositionWithStructuredErrorAndWritesNothing() {
        stubExistingDashboard();
        stubValidate(false, List.of(Map.of(
                "index", 0, "field", "columnPosition",
                "message", "columnPosition must be at least 1 (1-based grid column)")));

        CallToolResult result = call(Map.of(
                "name", "SalesOverview",
                "components", List.of(component("Bad Widget", "metric", 0, 1))));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured.get("status")).isEqualTo(400);
        @SuppressWarnings("unchecked")
        List<Object> errors = (List<Object>) structured.get("errors");
        assertThat(errors).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstError = (Map<String, Object>) errors.get(0);
        assertThat(firstError.get("source")).isEqualTo(Map.of("pointer", "/components/0/columnPosition"));

        wm.verify(0, WireMock.getRequestedFor(WireMock.urlMatching("/api/dashboard-components\\?.*")));
        wm.verify(0, WireMock.postRequestedFor(WireMock.urlMatching("/api/dashboard-components.*")));
        wm.verify(0, WireMock.patchRequestedFor(WireMock.urlMatching("/api/dashboard-components/.*")));
        wm.verify(0, WireMock.deleteRequestedFor(WireMock.urlMatching("/api/dashboard-components/.*")));
    }

    /** A component's "report" is a saved report name, resolved to reportId before the create call. */
    @Test
    void resolvesReportNameToReportIdOnCreate() {
        stubNewDashboard();
        stubValidate(true, List.of());
        wm.stubFor(get(urlEqualTo("/api/reports?filter[name][eq]=DealReport&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"R1\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/dashboard-components?filter[dashboardId][eq]=" + DASHBOARD_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        stubCreateComponent("Deal Summary", "C1");

        Map<String, Object> comp = Map.of("title", "Deal Summary", "componentType", "metric",
                "columnPosition", 1, "rowPosition", 1, "report", "DealReport");
        CallToolResult result = call(Map.of("name", "SalesOverview", "components", List.of(comp)));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/dashboard-components"))
                .withRequestBody(matchingJsonPath("$.data.attributes.reportId", equalTo("R1"))));
    }

    @Test
    void reportNotFoundFailsWithPlainError() {
        stubNewDashboard();
        wm.stubFor(get(urlEqualTo("/api/reports?filter[name][eq]=Missing&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        Map<String, Object> comp = Map.of("title", "Deal Summary", "componentType", "metric",
                "columnPosition", 1, "rowPosition", 1, "report", "Missing");
        CallToolResult result = call(Map.of("name", "SalesOverview", "components", List.of(comp)));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("Missing").contains("not found");
    }

    // =========================================================================
    // Stub helpers
    // =========================================================================

    private void stubNewDashboard() {
        wm.stubFor(get(urlEqualTo("/api/dashboards?filter[name][eq]=SalesOverview&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(post(urlEqualTo("/api/dashboards"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + DASHBOARD_ID + "\"}}")));
    }

    private void stubExistingDashboard() {
        wm.stubFor(get(urlEqualTo("/api/dashboards?filter[name][eq]=SalesOverview&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + DASHBOARD_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/dashboards/" + DASHBOARD_ID))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + DASHBOARD_ID + "\",\"attributes\":{\"name\":\"SalesOverview\"}}}")));
    }

    private void stubCreateComponent(String title, String id) {
        wm.stubFor(post(urlEqualTo("/api/dashboard-components"))
                .withRequestBody(matchingJsonPath("$.data.attributes.title", equalTo(title)))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + id + "\"}}")));
    }

    @SuppressWarnings("unchecked")
    private void stubExistingComponents(Map<String, Object>... rows) {
        StringBuilder data = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) data.append(',');
            Map<String, Object> row = rows[i];
            data.append("{\"id\":\"").append(row.get("id")).append("\",\"attributes\":{")
                    .append("\"title\":\"").append(row.get("title")).append("\",")
                    .append("\"componentType\":\"").append(row.get("componentType")).append("\",")
                    .append("\"columnPosition\":").append(row.get("columnPosition")).append(',')
                    .append("\"rowPosition\":").append(row.get("rowPosition")).append(',')
                    .append("\"columnSpan\":").append(row.get("columnSpan")).append(',')
                    .append("\"rowSpan\":").append(row.get("rowSpan")).append(',')
                    .append("\"config\":{},")
                    .append("\"sortOrder\":").append(row.get("sortOrder"))
                    .append("},\"relationships\":{\"dashboardId\":{\"data\":{\"id\":\"" + DASHBOARD_ID + "\"}}}}");
        }
        data.append(']');
        wm.stubFor(get(urlEqualTo("/api/dashboard-components?filter[dashboardId][eq]=" + DASHBOARD_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":" + data + "}")));
    }

    /**
     * Stubs {@code POST /api/dashboards/{id}/validate}. {@code fieldErrors} entries carry
     * {@code index}/{@code field}/{@code message}, matching the controller's per-component
     * {@code {id, errors:[{field,message}]}} result shape.
     */
    private void stubValidate(boolean valid, List<Map<String, Object>> fieldErrors) {
        StringBuilder componentsJson = new StringBuilder("[");
        if (!valid) {
            for (Map<String, Object> fe : fieldErrors) {
                if (componentsJson.length() > 1) componentsJson.append(',');
                componentsJson.append("{\"id\":null,\"errors\":[{\"field\":\"")
                        .append(fe.get("field")).append("\",\"message\":\"")
                        .append(fe.get("message")).append("\"}]}");
            }
        }
        componentsJson.append(']');
        String body = "{\"valid\":" + valid + ",\"components\":" + componentsJson + "}";
        wm.stubFor(post(urlEqualTo("/api/dashboards/" + DASHBOARD_ID + "/validate"))
                .willReturn(aResponse().withStatus(200).withBody(body)));
    }
}
