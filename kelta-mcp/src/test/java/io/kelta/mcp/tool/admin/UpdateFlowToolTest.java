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
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class UpdateFlowToolTest {

    private static final String FLOW_ID = "33333333-3333-3333-3333-333333333333";

    private WireMockServer wm;
    private UpdateFlowTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new UpdateFlowTool(client);
        RequestPatHolder.set("klt_update_flow_test");
        wm.stubFor(patch(urlEqualTo("/api/flows/" + FLOW_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + FLOW_ID + "\"}}")));
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_flow", args, null));
    }

    @Test
    void patchesFlowWithSuppliedAttributes() {
        CallToolResult result = call(Map.of(
                "id", FLOW_ID,
                "name", "Nightly sync",
                "active", false,
                "triggerConfig", Map.of("cron", "0 0 * * *"),
                "definition", Map.of("StartAt", "Fetch"),
                "stepLogging", "ERRORS_ONLY",
                "retentionDays", 30));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(1, WireMock.patchRequestedFor(urlEqualTo("/api/flows/" + FLOW_ID))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("flows")))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo(FLOW_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("Nightly sync")))
                .withRequestBody(matchingJsonPath("$.data.attributes.active", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.data.attributes.triggerConfig.cron", equalTo("0 0 * * *")))
                .withRequestBody(matchingJsonPath("$.data.attributes.definition.StartAt", equalTo("Fetch")))
                .withRequestBody(matchingJsonPath("$.data.attributes.stepLogging", equalTo("ERRORS_ONLY")))
                .withRequestBody(matchingJsonPath("$.data.attributes.retentionDays", equalTo("30")))
                .withRequestBody(matchingJsonPath("$.data.attributes.description", absent())));
    }

    @Test
    void blankRunAsUserIdAndZeroRetentionClearTheOverrides() {
        call(Map.of("id", FLOW_ID, "runAsUserId", "", "retentionDays", 0));

        wm.verify(1, WireMock.patchRequestedFor(urlEqualTo("/api/flows/" + FLOW_ID))
                .withRequestBody(WireMock.equalToJson(
                        "{\"data\":{\"type\":\"flows\",\"id\":\"" + FLOW_ID + "\","
                        + "\"attributes\":{\"runAsUserId\":null,\"retentionDays\":null}}}")));
    }

    @Test
    void rejectsWhenNothingToUpdate() {
        CallToolResult result = call(Map.of("id", FLOW_ID));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
