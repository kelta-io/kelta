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

class UpdatePicklistValueToolTest {

    private static final String VALUE_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private WireMockServer wm;
    private UpdatePicklistValueTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new UpdatePicklistValueTool(client);
        RequestPatHolder.set("klt_update_value");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void rejectsWithoutId() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_picklist_value",
                        Map.of("label", "Open"), null));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsWhenNoFieldsProvided() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_picklist_value",
                        Map.of("id", VALUE_UUID), null));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void patchesOnlyProvidedAttributes() {
        wm.stubFor(patch(urlEqualTo("/api/picklist-values/" + VALUE_UUID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_picklist_value", Map.of(
                        "id", VALUE_UUID,
                        "label", "Open (updated)",
                        "sortOrder", 3), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.patchRequestedFor(
                urlEqualTo("/api/picklist-values/" + VALUE_UUID))
                .withHeader("Authorization", equalTo("Bearer klt_update_value"))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("picklist-values")))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo(VALUE_UUID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.label",
                        equalTo("Open (updated)")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("3"))));
    }

    @Test
    void forwardsColorWhenProvided() {
        wm.stubFor(patch(urlEqualTo("/api/picklist-values/" + VALUE_UUID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_picklist_value", Map.of(
                        "id", VALUE_UUID,
                        "color", "#22C55E"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.patchRequestedFor(
                urlEqualTo("/api/picklist-values/" + VALUE_UUID))
                .withRequestBody(matchingJsonPath("$.data.attributes.color", equalTo("#22C55E"))));
    }
}
