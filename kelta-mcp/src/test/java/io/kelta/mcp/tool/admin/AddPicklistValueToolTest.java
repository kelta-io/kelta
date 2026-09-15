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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class AddPicklistValueToolTest {

    private static final String PICKLIST_UUID = "11111111-2222-3333-4444-555555555555";

    private WireMockServer wm;
    private AddPicklistValueTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new AddPicklistValueTool(client);
        RequestPatHolder.set("klt_add_value");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void rejectsWithoutPicklistValueOrLabel() {
        assertThat(tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("add_picklist_value",
                        Map.of("value", "OPEN", "label", "Open"), null)).isError())
                .isEqualTo(Boolean.TRUE);
        assertThat(tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("add_picklist_value",
                        Map.of("picklist", "p", "label", "Open"), null)).isError())
                .isEqualTo(Boolean.TRUE);
        assertThat(tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("add_picklist_value",
                        Map.of("picklist", "p", "value", "OPEN"), null)).isError())
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void postsValueDirectlyWhenPicklistIsUuid() {
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"v1\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("add_picklist_value", Map.of(
                        "picklist", PICKLIST_UUID,
                        "value", "OPEN",
                        "label", "Open"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withHeader("Authorization", equalTo("Bearer klt_add_value"))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("picklist-values")))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("OPEN")))
                .withRequestBody(matchingJsonPath("$.data.attributes.label", equalTo("Open")))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceType",
                        equalTo("GLOBAL")))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceId",
                        equalTo(PICKLIST_UUID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.globalPicklistId",
                        equalTo(PICKLIST_UUID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.data.attributes.isActive", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.data.attributes.isDefault",
                        equalTo("false"))));
    }

    @Test
    void resolvesNameToIdBeforePosting() {
        wm.stubFor(get(urlEqualTo(
                "/api/global-picklists?filter[name][EQ]=stages&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"type\":\"global-picklists\",\"id\":\"" + PICKLIST_UUID
                                + "\"}]}")));
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("add_picklist_value", Map.of(
                        "picklist", "stages",
                        "value", "CLOSED",
                        "label", "Closed",
                        "sortOrder", 5,
                        "isActive", false,
                        "isDefault", true), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceId",
                        equalTo(PICKLIST_UUID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("5")))
                .withRequestBody(matchingJsonPath("$.data.attributes.isActive",
                        equalTo("false")))
                .withRequestBody(matchingJsonPath("$.data.attributes.isDefault",
                        equalTo("true"))));
    }

    @Test
    void forwardsColorWhenProvided() {
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"v1\"}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("add_picklist_value", Map.of(
                        "picklist", PICKLIST_UUID,
                        "value", "OPEN",
                        "label", "Open",
                        "color", "#22C55E"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.color", equalTo("#22C55E"))));
    }
}
