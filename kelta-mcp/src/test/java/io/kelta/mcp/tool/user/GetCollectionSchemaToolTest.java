package io.kelta.mcp.tool.user;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class GetCollectionSchemaToolTest {

    private static final String SCHEMA_BODY = """
            {"name":"customers","displayName":"Customers","systemCollection":false,\
            "fields":[{"name":"phone","type":"STRING","required":false,"isRelationship":false}]}""";
    private static final String COLLECTION_BODY = """
            {"data":{"id":"ec000100-0000-0000-0000-000000000003",\
            "type":"collections",\
            "attributes":{"name":"customers","systemCollection":false}}}""";
    private static final String RULES_BODY = """
            {"data":[{"id":"r1","type":"validation-rules",\
            "attributes":{"name":"amount-check"}}]}""";

    private WireMockServer wm;
    private GetCollectionSchemaTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new GetCollectionSchemaTool(client);
        RequestPatHolder.set("klt_schema_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    @Test
    void extractCollectionIdParsesJsonApiSingleResource() {
        assertThat(GetCollectionSchemaTool.extractCollectionId(COLLECTION_BODY))
                .isEqualTo("ec000100-0000-0000-0000-000000000003");
    }

    @Test
    void extractCollectionIdReturnsNullForMalformedBodies() {
        assertThat(GetCollectionSchemaTool.extractCollectionId(null)).isNull();
        assertThat(GetCollectionSchemaTool.extractCollectionId("")).isNull();
        assertThat(GetCollectionSchemaTool.extractCollectionId("not json")).isNull();
        assertThat(GetCollectionSchemaTool.extractCollectionId("{\"data\":{}}")).isNull();
    }

    @Test
    void callsSchemaEndpointOnceAndEmbedsValidationRulesByDefault() {
        wm.stubFor(get(urlEqualTo("/api/collections/customers/schema"))
                .willReturn(aResponse().withStatus(200).withBody(SCHEMA_BODY)));
        wm.stubFor(get(urlEqualTo("/api/collections/customers"))
                .willReturn(aResponse().withStatus(200).withBody(COLLECTION_BODY)));
        wm.stubFor(get(urlEqualTo(
                "/api/validation-rules?filter[collectionId][EQ]=ec000100-0000-0000-0000-000000000003&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(RULES_BODY)));

        SyncToolSpecification spec = tool.toSpecification();
        CallToolResult result = spec.callHandler().apply(null,
                new CallToolRequest("get_collection_schema",
                        Map.of("collection", "customers"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text)
                .contains("\"fields\"")
                .contains("phone")
                .contains("\"validationRules\"")
                .contains("amount-check");

        wm.verify(WireMock.getRequestedFor(urlEqualTo("/api/collections/customers/schema")));
        wm.verify(WireMock.getRequestedFor(urlEqualTo(
                "/api/validation-rules?filter[collectionId][EQ]=ec000100-0000-0000-0000-000000000003&page[size]=200")));
    }

    @Test
    void carriesSystemMaintainedForEachField() {
        wm.stubFor(get(urlEqualTo("/api/collections/inspections/schema"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"name":"inspections","displayName":"Inspections","systemCollection":false,\
                        "fields":[\
                        {"name":"site","type":"STRING","required":true,"isRelationship":false,"systemMaintained":false},\
                        {"name":"failureCount","type":"INTEGER","required":false,"isRelationship":false,"systemMaintained":true}]}""")));

        CallToolResult result = tool.toSpecification().callHandler().apply(null,
                new CallToolRequest("get_collection_schema",
                        Map.of("collection", "inspections", "includeRules", false), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        JsonNode fields = new ObjectMapper()
                .readTree(((TextContent) result.content().get(0)).text()).get("fields");
        assertThat(fields).hasSize(2);
        for (JsonNode field : fields) {
            assertThat(field.has("systemMaintained"))
                    .as("systemMaintained on %s", field.get("name").asString()).isTrue();
        }
        assertThat(fields.get(0).get("systemMaintained").asBoolean()).isFalse();
        assertThat(fields.get(1).get("systemMaintained").asBoolean()).isTrue();
    }

    @Test
    void passesPicklistValuesThroughFromTheSchemaResponse() {
        wm.stubFor(get(urlEqualTo("/api/collections/tickets/schema"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"name":"tickets","displayName":"Tickets","systemCollection":false,\
                        "fields":[{"name":"priority","type":"PICKLIST","required":false,"isRelationship":false,\
                        "picklistValues":[\
                        {"value":"LOW","label":"Low","isDefault":true,"sortOrder":1},\
                        {"value":"HIGH","label":"High","isDefault":false,"sortOrder":2}]}]}""")));

        CallToolResult result = tool.toSpecification().callHandler().apply(null,
                new CallToolRequest("get_collection_schema",
                        Map.of("collection", "tickets", "includeRules", false), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        JsonNode values = new ObjectMapper()
                .readTree(((TextContent) result.content().get(0)).text())
                .get("fields").get(0).get("picklistValues");
        assertThat(values).hasSize(2);
        assertThat(values.get(0).get("value").asString()).isEqualTo("LOW");
        assertThat(values.get(0).get("label").asString()).isEqualTo("Low");
        assertThat(values.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(values.get(0).get("sortOrder").asInt()).isEqualTo(1);
        assertThat(values.get(1).get("value").asString()).isEqualTo("HIGH");
    }

    @Test
    void describesPicklistValues() {
        assertThat(tool.toSpecification().tool().description()).contains("picklistValues");
    }

    @Test
    void skipsValidationRulesFetchWhenIncludeRulesIsFalse() {
        wm.stubFor(get(urlEqualTo("/api/collections/customers/schema"))
                .willReturn(aResponse().withStatus(200).withBody(SCHEMA_BODY)));

        CallToolResult result = tool.toSpecification().callHandler().apply(null,
                new CallToolRequest("get_collection_schema",
                        Map.of("collection", "customers", "includeRules", false), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"fields\"").doesNotContain("\"validationRules\"");
        wm.verify(0, WireMock.getRequestedFor(urlEqualTo("/api/collections/customers")));
        wm.verify(0, WireMock.getRequestedFor(WireMock.urlMatching("/api/validation-rules.*")));
    }

    @Test
    void surfacesNullValidationRulesWhenCollectionIdCannotBeResolved() {
        wm.stubFor(get(urlEqualTo("/api/collections/customers/schema"))
                .willReturn(aResponse().withStatus(200).withBody(SCHEMA_BODY)));
        wm.stubFor(get(urlEqualTo("/api/collections/customers"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{}}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(null,
                new CallToolRequest("get_collection_schema",
                        Map.of("collection", "customers"), null));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"validationRules\":null");
        wm.verify(0, WireMock.getRequestedFor(WireMock.urlMatching("/api/validation-rules.*")));
    }

    @Test
    void surfacesErrorWhenSchemaLookupFails() {
        wm.stubFor(get(urlEqualTo("/api/collections/nope/schema"))
                .willReturn(aResponse().withStatus(404).withBody(
                        "{\"errors\":[{\"status\":\"404\",\"detail\":\"No collection named 'nope'\"}]}")));

        CallToolResult result = tool.toSpecification().callHandler().apply(null,
                new CallToolRequest("get_collection_schema",
                        Map.of("collection", "nope"), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("404").contains("No collection named 'nope'");
        wm.verify(0, WireMock.getRequestedFor(WireMock.urlMatching("/api/validation-rules.*")));
    }

    @Test
    void rejectsCallWithoutCollectionArg() {
        SyncToolSpecification spec = tool.toSpecification();
        CallToolResult result = spec.callHandler().apply(null,
                new CallToolRequest("get_collection_schema", Map.of(), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
