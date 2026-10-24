package io.kelta.mcp.tool.user;

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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class GetRecordToolTest {

    private static final String RECORD_ID = "44444444-4444-4444-4444-444444444444";

    private WireMockServer wm;
    private GetRecordTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new GetRecordTool(client);
        RequestPatHolder.set("klt_get_record_test");
        wm.stubFor(get(urlPathEqualTo("/api/accounts/" + RECORD_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + RECORD_ID + "\"}}")));
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("get_record", args, null));
    }

    @Test
    void getsTheRecordByCollectionAndId() {
        CallToolResult result = call(Map.of("collection", "accounts", "id", RECORD_ID));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/api/accounts/" + RECORD_ID))
                .withQueryParam("include", absent()));
    }

    @Test
    void passesIncludeAsAQueryParameter() {
        call(Map.of("collection", "accounts", "id", RECORD_ID, "include", "owner,contacts"));

        wm.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/api/accounts/" + RECORD_ID))
                .withQueryParam("include", equalTo("owner,contacts")));
    }

    @Test
    void requiresCollectionAndId() {
        CallToolResult result = call(Map.of("collection", "accounts"));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
