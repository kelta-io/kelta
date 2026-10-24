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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code displayFieldName} resolves to the field's UUID and PATCHes
 * {@code displayFieldId} — the worker has no displayFieldName attribute, so
 * the previous pass-through was silently ignored.
 */
class UpdateCollectionToolTest {

    private static final String COLLECTION_ID = "11111111-1111-1111-1111-111111111111";

    private WireMockServer wm;
    private UpdateCollectionTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new UpdateCollectionTool(client);
        RequestPatHolder.set("klt_update_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void resolvesDisplayFieldNameToDisplayFieldId() {
        wm.stubFor(get(urlEqualTo("/api/fields?filter[collectionId][EQ]=" + COLLECTION_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"fid-name\",\"type\":\"fields\",\"attributes\":{\"name\":\"nameEn\"}}]}")));
        wm.stubFor(patch(urlEqualTo("/api/collections/" + COLLECTION_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + COLLECTION_ID + "\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_collection", Map.of(
                        "id", COLLECTION_ID,
                        "displayFieldName", "nameEn"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/collections/" + COLLECTION_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.displayFieldId", equalTo("fid-name"))));
    }

    @Test
    void acceptsCollectionNameAsId() {
        wm.stubFor(get(urlEqualTo("/api/collections?filter[name][eq]=projects"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"type\":\"collections\",\"id\":\"" + COLLECTION_ID + "\"}]}")));
        wm.stubFor(patch(urlEqualTo("/api/collections/" + COLLECTION_ID))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_collection", Map.of(
                        "id", "projects",
                        "displayName", "Projects"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/collections/" + COLLECTION_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.displayName", equalTo("Projects"))));
    }

    @Test
    void errorsWhenDisplayFieldMissing() {
        wm.stubFor(get(urlEqualTo("/api/fields?filter[collectionId][EQ]=" + COLLECTION_ID + "&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_collection", Map.of(
                        "id", COLLECTION_ID,
                        "displayFieldName", "ghost"), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsNonStringIdInsteadOfSendingItsToString() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_collection", Map.of(
                        "id", 123,
                        "displayName", "Projects"), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
