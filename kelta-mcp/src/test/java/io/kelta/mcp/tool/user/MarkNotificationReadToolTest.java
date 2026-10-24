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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class MarkNotificationReadToolTest {

    private WireMockServer wm;
    private MarkNotificationReadTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new MarkNotificationReadTool(client);
        RequestPatHolder.set("klt_mark_read_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void rejectsCallWithoutId() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("mark_notification_read", Map.of(), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void postsToTheNotificationsReadPath() {
        wm.stubFor(post(urlEqualTo("/api/me/notifications/n-1/read"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":{\"id\":\"n-1\"},\"meta\":{\"unreadCount\":0}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("mark_notification_read", Map.of("id", "n-1"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/me/notifications/n-1/read"))
                .withHeader("Authorization", equalTo("Bearer klt_mark_read_test"))
                .withRequestBody(equalToJson("{}")));
    }

    @Test
    void encodesTheIdIntoOnePathSegment() {
        wm.stubFor(post(urlEqualTo("/api/me/notifications/a%2Fb/read"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("mark_notification_read", Map.of("id", "a/b"), null));

        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/me/notifications/a%2Fb/read")));
    }

    @Test
    void surfacesNotFoundAsAnError() {
        wm.stubFor(post(urlEqualTo("/api/me/notifications/missing/read"))
                .willReturn(aResponse().withStatus(404).withBody("{\"errors\":[{\"status\":\"404\"}]}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("mark_notification_read", Map.of("id", "missing"), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }
}
