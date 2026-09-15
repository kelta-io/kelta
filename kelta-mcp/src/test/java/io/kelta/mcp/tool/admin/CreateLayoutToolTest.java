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
 * The tool targets the worker's kebab-case system-collection routes with the
 * real attribute shapes: page-layouts get a resolved {@code collectionId},
 * layout-sections a {@code layoutId} + {@code heading}, and layout-fields a
 * {@code sectionId} + {@code fieldId} resolved from the entry's fieldName.
 * (The previous camelCase paths — /api/pageLayouts etc. — 404 on the worker.)
 */
class CreateLayoutToolTest {

    private static final String COLLECTION_ID = "11111111-1111-1111-1111-111111111111";

    private WireMockServer wm;
    private CreateLayoutTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new CreateLayoutTool(client);
        RequestPatHolder.set("klt_layout_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private void stubCollectionAndFields() {
        wm.stubFor(get(urlEqualTo("/api/collections?filter[name][eq]=projects"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"type\":\"collections\",\"id\":\"" + COLLECTION_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo("/api/fields?filter[collectionId][EQ]=" + COLLECTION_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":["
                        + "{\"id\":\"fid-name\",\"type\":\"fields\",\"attributes\":{\"name\":\"name\"}},"
                        + "{\"id\":\"fid-owner\",\"type\":\"fields\",\"attributes\":{\"name\":\"owner\"}},"
                        + "{\"id\":\"fid-stage\",\"type\":\"fields\",\"attributes\":{\"name\":\"stage\"}}"
                        + "]}")));
    }

    @Test
    void rejectsWithoutNameOrCollection() {
        CallToolResult r1 = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "collectionName", "projects",
                        "sections", List.of()), null));
        assertThat(r1.isError()).isEqualTo(Boolean.TRUE);

        CallToolResult r2 = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "name", "main",
                        "sections", List.of()), null));
        assertThat(r2.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void createsLayoutSectionsAndFieldsInOrder() {
        stubCollectionAndFields();
        wm.stubFor(post(urlEqualTo("/api/page-layouts"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"L1\",\"type\":\"page-layouts\"}}")));
        wm.stubFor(post(urlEqualTo("/api/layout-sections"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"S1\",\"type\":\"layout-sections\"}}")));
        wm.stubFor(post(urlEqualTo("/api/layout-fields"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"F1\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "name", "ProjectsMain",
                        "collectionName", "projects",
                        "sections", List.of(
                                Map.of("sectionName", "Overview",
                                        "fields", List.of(
                                                Map.of("fieldName", "name"),
                                                Map.of("fieldName", "owner"))),
                                Map.of("sectionName", "Status",
                                        "fields", List.of(Map.of("fieldName", "stage"))))), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/page-layouts"))
                .withHeader("Authorization", equalTo("Bearer klt_layout_test"))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("page-layouts")))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("ProjectsMain")))
                .withRequestBody(matchingJsonPath("$.data.attributes.collectionId", equalTo(COLLECTION_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.layoutType", equalTo("DETAIL"))));
        wm.verify(2, WireMock.postRequestedFor(urlEqualTo("/api/layout-sections")));
        wm.verify(3, WireMock.postRequestedFor(urlEqualTo("/api/layout-fields")));
        // sortOrder defaulting, heading mapping + child references
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/layout-sections"))
                .withRequestBody(matchingJsonPath("$.data.attributes.layoutId", equalTo("L1")))
                .withRequestBody(matchingJsonPath("$.data.attributes.heading", equalTo("Status")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("1"))));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/layout-fields"))
                .withRequestBody(matchingJsonPath("$.data.attributes.sectionId", equalTo("S1")))
                .withRequestBody(matchingJsonPath("$.data.attributes.fieldId", equalTo("fid-stage"))));
    }

    @Test
    void placesFieldsInZeroBasedColumnsWhenColumnNumberOmitted() {
        stubCollectionAndFields();
        wm.stubFor(post(urlEqualTo("/api/page-layouts"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"L1\",\"type\":\"page-layouts\"}}")));
        wm.stubFor(post(urlEqualTo("/api/layout-sections"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"S1\",\"type\":\"layout-sections\"}}")));
        wm.stubFor(post(urlEqualTo("/api/layout-fields"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"F1\"}}")));

        Map<String, Object> section = Map.of(
                "sectionName", "Overview",
                "columns", 2,
                "fields", List.of(
                        Map.of("fieldName", "name"),
                        Map.of("fieldName", "owner"),
                        Map.of("fieldName", "stage"),
                        Map.of("fieldName", "name")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "name", "ProjectsMain",
                        "collectionName", "projects",
                        "sections", List.of(section)), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        // two-column section, four fields, no explicit columnNumber -> 0,1,0,1
        for (int i = 0; i < 4; i++) {
            wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/layout-fields"))
                    .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo(String.valueOf(i))))
                    .withRequestBody(matchingJsonPath("$.data.attributes.columnNumber", equalTo(String.valueOf(i % 2)))));
        }
    }

    @Test
    void reportsUnknownFieldNamesInsteadOfPosting() {
        stubCollectionAndFields();
        wm.stubFor(post(urlEqualTo("/api/page-layouts"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"L1\"}}")));
        wm.stubFor(post(urlEqualTo("/api/layout-sections"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"S1\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "name", "L",
                        "collectionName", "projects",
                        "sections", List.of(Map.of("sectionName", "S",
                                "fields", List.of(Map.of("fieldName", "doesNotExist"))))), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0)).text())
                .contains("field not found on collection");
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/layout-fields")));
    }

    @Test
    void shortCircuitsWhenLayoutCreationFails() {
        stubCollectionAndFields();
        wm.stubFor(post(urlEqualTo("/api/page-layouts"))
                .willReturn(aResponse().withStatus(409).withBody("{}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "name", "L",
                        "collectionName", "projects",
                        "sections", List.of(Map.of("sectionName", "S",
                                "fields", List.of(Map.of("fieldName", "name"))))), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/layout-sections")));
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/layout-fields")));
    }
}
