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

class UpdateLayoutToolTest {

    private static final String LAYOUT_ID = "22222222-2222-2222-2222-222222222222";

    private WireMockServer wm;
    private UpdateLayoutTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new UpdateLayoutTool(client);
        RequestPatHolder.set("klt_update_layout_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("update_layout", args, null));
    }

    @Test
    void patchesOnlySuppliedAttributes() {
        wm.stubFor(patch(urlEqualTo("/api/page-layouts/" + LAYOUT_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + LAYOUT_ID + "\"}}")));

        CallToolResult result = call(Map.of(
                "id", LAYOUT_ID,
                "name", "Main Layout",
                "isDefault", true));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(1, WireMock.patchRequestedFor(urlEqualTo("/api/page-layouts/" + LAYOUT_ID))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("page-layouts")))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo(LAYOUT_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("Main Layout")))
                .withRequestBody(matchingJsonPath("$.data.attributes.isDefault", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.data.attributes.recordTypeName", absent())));
    }

    @Test
    void sendsRecordTypeName() {
        wm.stubFor(patch(urlEqualTo("/api/page-layouts/" + LAYOUT_ID))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        call(Map.of("id", LAYOUT_ID, "recordTypeName", "Enterprise"));

        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/page-layouts/" + LAYOUT_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes.recordTypeName", equalTo("Enterprise")))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", absent()))
                .withRequestBody(matchingJsonPath("$.data.attributes.isDefault", absent())));
    }

    @Test
    void rejectsWhenNothingToUpdate() {
        CallToolResult result = call(Map.of("id", LAYOUT_ID));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
