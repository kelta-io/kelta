package io.kelta.mcp.tool.user;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class CreateRecordToolTest {

    private WireMockServer wm;
    private CreateRecordTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new CreateRecordTool(client);
        RequestPatHolder.set("klt_create_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void rejectsCallWithoutCollection() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_record", Map.of("attributes", Map.of("name", "x")), null));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsCallWithEmptyAttributes() {
        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_record", Map.of(
                        "collection", "accounts",
                        "attributes", Map.of()), null));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void postsJsonApiBodyToCollectionEndpoint() {
        wm.stubFor(post(urlEqualTo("/api/accounts"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"type\":\"accounts\",\"id\":\"a1\",\"attributes\":{\"name\":\"Acme\"}}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_record", Map.of(
                        "collection", "accounts",
                        "attributes", Map.of("name", "Acme", "industry", "Software")), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/accounts"))
                .withHeader("Authorization", equalTo("Bearer klt_create_test"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.data.type", equalTo("accounts")))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("Acme")))
                .withRequestBody(matchingJsonPath("$.data.attributes.industry", equalTo("Software"))));
    }

    @Test
    void includesRelationshipsBlockWhenProvided() {
        wm.stubFor(post(urlEqualTo("/api/contacts"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{}}")));

        tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_record", Map.of(
                        "collection", "contacts",
                        "attributes", Map.of("firstName", "Alice"),
                        "relationships", Map.of(
                                "account", Map.of("data", Map.of("type", "accounts", "id", "a1")))
                ), null));

        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/contacts"))
                .withRequestBody(matchingJsonPath("$.data.relationships.account.data.id", equalTo("a1"))));
    }

    @Test
    void relaysGatewayUniqueViolationNamingFieldAndValue() {
        wm.stubFor(post(urlEqualTo("/api/codes"))
                .willReturn(aResponse().withStatus(409).withBody(
                        "{\"errors\":[{\"status\":\"409\",\"code\":\"UNIQUE_VIOLATION\",\"title\":\"Conflict\","
                                + "\"detail\":\"Unique constraint violation: field 'code' in collection 'codes' "
                                + "already has value 'A-1'\",\"source\":{\"pointer\":\"/data/attributes/code\"}}]}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("create_record", Map.of(
                        "collection", "codes",
                        "attributes", Map.of("code", "A-1")), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("409").contains("'code'").contains("'A-1'");
        Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
        Map<?, ?> error = (Map<?, ?>) ((List<?>) structured.get("errors")).get(0);
        assertThat(error.get("code")).isEqualTo("UNIQUE_VIOLATION");
        assertThat(error.get("source")).isEqualTo(Map.of("pointer", "/data/attributes/code"));
    }
}
