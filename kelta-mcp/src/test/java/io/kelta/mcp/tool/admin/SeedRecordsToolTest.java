package io.kelta.mcp.tool.admin;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class SeedRecordsToolTest {

    private static final String ACCOUNT_LOOKUP =
            "/api/accounts?filter[name][eq]=Northwind+Traders&page[size]=1";

    private WireMockServer wm;
    private SeedRecordsTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new SeedRecordsTool(client);
        RequestPatHolder.set("klt_seed_records");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(null, new CallToolRequest("seed_records", args, null));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void createsRecordsThroughAtomicOperationsWithTheCallersToken() {
        wm.stubFor(post(urlEqualTo("/api/operations")).willReturn(aResponse().withStatus(200).withBody(
                "{\"atomic:results\":[{\"data\":{\"type\":\"accounts\",\"id\":\"a1\"}},"
                        + "{\"data\":{\"type\":\"accounts\",\"id\":\"a2\"}}]}")));

        CallToolResult result = call(Map.of(
                "collection", "accounts",
                "records", List.of(
                        Map.of("name", "Northwind Traders", "industry", "RETAIL"),
                        Map.of("name", "Contoso", "employees", 250))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("\"created\":2").contains("\"ids\":[\"a1\",\"a2\"]");
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/operations"))
                .withHeader("Authorization", equalTo("Bearer klt_seed_records"))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][0].op", equalTo("add")))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][0].data.type", equalTo("accounts")))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][0].data.attributes.name",
                        equalTo("Northwind Traders")))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][1].data.attributes.employees",
                        equalTo("250"))));
    }

    @Test
    void resolvesReferenceFieldsFromTheParentsNaturalKeyOncePerKey() {
        wm.stubFor(get(urlEqualTo(ACCOUNT_LOOKUP)).willReturn(aResponse().withStatus(200).withBody(
                "{\"data\":[{\"type\":\"accounts\",\"id\":\"acc-1\",\"attributes\":{\"name\":\"Northwind Traders\"}}]}")));
        wm.stubFor(post(urlEqualTo("/api/operations")).willReturn(aResponse().withStatus(200).withBody(
                "{\"atomic:results\":[{\"data\":{\"id\":\"c1\"}},{\"data\":{\"id\":\"c2\"}}]}")));

        CallToolResult result = call(Map.of(
                "collection", "contacts",
                "records", List.of(
                        Map.of("email", "maria@example.com", "account", "Northwind Traders"),
                        Map.of("email", "james@example.com", "account", "Northwind Traders")),
                "references", Map.of("account", "accounts.name")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(1, WireMock.getRequestedFor(urlEqualTo(ACCOUNT_LOOKUP)));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/operations"))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][0].data.attributes.account",
                        equalTo("acc-1")))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][1].data.attributes.account",
                        equalTo("acc-1"))));
    }

    @Test
    void unknownReferenceKeyFailsBeforeAnythingIsWritten() {
        wm.stubFor(get(urlPathEqualTo("/api/accounts"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        CallToolResult result = call(Map.of(
                "collection", "contacts",
                "records", List.of(Map.of("email", "x@example.com", "account", "Nobody Inc")),
                "references", Map.of("account", "accounts.name")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("no accounts record with name \"Nobody Inc\"");
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/operations")));
    }

    @Test
    void splitsLargeSeedsIntoBatchesOfOneHundred() {
        wm.stubFor(post(urlEqualTo("/api/operations"))
                .willReturn(aResponse().withStatus(200).withBody("{\"atomic:results\":[]}")));
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < 250; i++) records.add(Map.of("name", "r" + i));

        CallToolResult result = call(Map.of("collection", "items", "records", records));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(3, WireMock.postRequestedFor(urlEqualTo("/api/operations")));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/operations"))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][99].data.attributes.name", equalTo("r99"))));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/operations"))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][0].data.attributes.name", equalTo("r200"))));
    }

    @Test
    void laterBatchFailureReportsWhatTheEarlierBatchesCreated() {
        wm.stubFor(post(urlEqualTo("/api/operations"))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][0].data.attributes.name", equalTo("r0")))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"atomic:results\":[{\"data\":{\"id\":\"x1\"}}]}")));
        wm.stubFor(post(urlEqualTo("/api/operations"))
                .withRequestBody(matchingJsonPath("$['atomic:operations'][0].data.attributes.name", equalTo("r100")))
                .willReturn(aResponse().withStatus(422).withBody(
                        "{\"errors\":[{\"status\":\"422\",\"detail\":\"name: Value must be unique\"}]}")));
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < 101; i++) records.add(Map.of("name", "r" + i));

        CallToolResult result = call(Map.of("collection", "items", "records", records));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).startsWith("Created 1 of 101 records").contains("Value must be unique");
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
        assertThat(structured.get("created")).isEqualTo(1);
        assertThat(structured.get("status")).isEqualTo(422);
    }

    @Test
    void firstBatchFailureIsTheGatewayError() {
        wm.stubFor(post(urlEqualTo("/api/operations")).willReturn(aResponse().withStatus(404).withBody(
                "{\"errors\":[{\"status\":\"404\",\"detail\":\"Collection not found: nope\"}]}")));

        CallToolResult result = call(Map.of("collection", "nope", "records", List.of(Map.of("name", "a"))));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("HTTP 404").contains("Collection not found");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThat(call(Map.of("records", List.of(Map.of("a", 1)))).isError()).isEqualTo(Boolean.TRUE);
        assertThat(call(Map.of("collection", "items", "records", List.of())).isError()).isEqualTo(Boolean.TRUE);
        assertThat(call(Map.of("collection", "items", "records", List.of("not-an-object"))).isError())
                .isEqualTo(Boolean.TRUE);
        assertThat(call(Map.of("collection", "items", "records", List.of(Map.of("a", 1)),
                "references", Map.of("account", "accounts"))).isError()).isEqualTo(Boolean.TRUE);
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= SeedRecordsTool.MAX_RECORDS; i++) tooMany.add(Map.of("n", i));
        assertThat(call(Map.of("collection", "items", "records", tooMany)).isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/operations")));
    }
}
