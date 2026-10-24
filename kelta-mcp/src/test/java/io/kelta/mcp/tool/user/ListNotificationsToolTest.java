package io.kelta.mcp.tool.user;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ListNotificationsToolTest {

    private WireMockServer wm;
    private ListNotificationsTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ListNotificationsTool(client);
        RequestPatHolder.set("klt_notifications_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void defaultsToAllNotificationsWithNoQuery() {
        wm.stubFor(get(urlEqualTo("/api/me/notifications"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[],\"meta\":{\"unreadCount\":0}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("list_notifications", Map.of(), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.getRequestedFor(urlEqualTo("/api/me/notifications"))
                .withHeader("Authorization", equalTo("Bearer klt_notifications_test")));
    }

    @Test
    void unreadOnlyAndPagingGoOnTheQuery() {
        wm.stubFor(get(urlEqualTo("/api/me/notifications?unread=true&page[size]=5&page[number]=2"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("list_notifications", Map.of(
                        "unreadOnly", true,
                        "pageSize", 5,
                        "pageNumber", 2), null));

        wm.verify(WireMock.getRequestedFor(
                urlEqualTo("/api/me/notifications?unread=true&page[size]=5&page[number]=2")));
    }

    @Test
    void unreadOnlyFalseOmitsTheFilter() {
        wm.stubFor(get(urlEqualTo("/api/me/notifications"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("list_notifications", Map.of("unreadOnly", false), null));

        wm.verify(WireMock.getRequestedFor(urlEqualTo("/api/me/notifications")));
    }

    @Test
    void declaresItsInputs() {
        assertThat(tool.toSpecification().tool().inputSchema()).extractingByKey("properties")
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("unreadOnly", "pageSize", "pageNumber");
        assertThat(tool.toSpecification().tool().annotations().readOnlyHint()).isTrue();
    }
}
