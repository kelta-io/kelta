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
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * create_layout is deprecated and now a thin translation layer over {@link ApplyLayoutTool}:
 * it maps its legacy fieldName/sectionName/columnNumber argument shape onto apply_layout's
 * tree body and makes the same single gateway call — {@code PUT
 * /api/collections/{collection}/layouts/{name}/tree} — instead of one POST per
 * layout/section/field.
 */
class CreateLayoutToolTest {

    private WireMockServer wm;
    private CreateLayoutTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new CreateLayoutTool(new ApplyLayoutTool(client));
        RequestPatHolder.set("klt_layout_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
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
    void createsLayoutSectionsAndFieldsInOneTreeCall() {
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/ProjectsMain/tree"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"layoutId\":\"L1\",\"created\":3,\"updated\":0,\"deleted\":0,\"unchanged\":0}")));

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
        wm.verify(1, WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/ProjectsMain/tree"))
                .withHeader("Authorization", equalTo("Bearer klt_layout_test")));
        wm.verify(WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/ProjectsMain/tree"))
                .withRequestBody(matchingJsonPath("$.sections[0].heading", equalTo("Overview")))
                .withRequestBody(matchingJsonPath("$.sections[1].heading", equalTo("Status")))
                .withRequestBody(matchingJsonPath("$.sections[1].fields[0].name", equalTo("stage"))));
    }

    @Test
    void placesFieldsInZeroBasedColumnsWhenColumnNumberOmitted() {
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/ProjectsMain/tree"))
                .willReturn(aResponse().withStatus(200).withBody("{\"layoutId\":\"L1\"}")));

        Map<String, Object> section = Map.of(
                "sectionName", "Overview",
                "columns", 2,
                "fields", List.of(
                        Map.of("fieldName", "name"),
                        Map.of("fieldName", "owner"),
                        Map.of("fieldName", "stage"),
                        Map.of("fieldName", "notes")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "name", "ProjectsMain",
                        "collectionName", "projects",
                        "sections", List.of(section)), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        // two-column section, four fields, no explicit columnNumber -> 0,1,0,1
        for (int i = 0; i < 4; i++) {
            wm.verify(WireMock.putRequestedFor(urlEqualTo("/api/collections/projects/layouts/ProjectsMain/tree"))
                    .withRequestBody(matchingJsonPath(
                            "$.sections[0].fields[" + i + "].column", equalTo(String.valueOf(i % 2)))));
        }
    }

    @Test
    void surfacesGatewayFailureAsError() {
        wm.stubFor(put(urlEqualTo("/api/collections/projects/layouts/L/tree"))
                .willReturn(aResponse().withStatus(409).withBody("{\"errors\":[{\"detail\":\"conflict\"}]}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_layout", Map.of(
                        "name", "L",
                        "collectionName", "projects",
                        "sections", List.of(Map.of("sectionName", "S",
                                "fields", List.of(Map.of("fieldName", "name"))))), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }
}
