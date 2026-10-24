package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * apply_collection against a WireMock gateway: a first call on an empty tenant creates the
 * collection, every field, the field-scoped picklist values and the display field; an identical
 * second call writes nothing; a single changed attribute PATCHes only its own record; and a
 * failing field is reported next to the parts that succeeded.
 */
class ApplyCollectionToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION_ID = "c0000000-0000-0000-0000-000000000001";
    private static final String TITLE_ID = "f0000000-0000-0000-0000-000000000001";
    private static final String STAGE_ID = "f0000000-0000-0000-0000-000000000002";
    private static final String OPEN_ID = "v1";
    private static final String WON_ID = "v2";
    private static final String COLLECTION_LOOKUP = "/api/collections?filter[name][eq]=deals&page[size]=1";

    private WireMockServer wm;
    private ApplyCollectionTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyCollectionTool(client);
        RequestPatHolder.set("klt_apply_collection_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private static Map<String, Object> args(boolean titleRequired, String wonLabel) {
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("fieldName", "title");
        title.put("type", "text");
        title.put("required", titleRequired);

        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("fieldName", "stage");
        stage.put("type", "picklist");
        stage.put("values", List.of(
                Map.of("value", "OPEN", "label", "Open"),
                Map.of("value", "WON", "label", wonLabel, "color", "#22C55E")));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "deals");
        args.put("displayName", "Deals");
        args.put("fields", List.of(title, stage));
        args.put("displayFieldName", "title");
        return args;
    }

    private static String fieldLookup(String name) {
        return "/api/fields?filter[collectionId][eq]=" + COLLECTION_ID + "&filter[name][eq]=" + name + "&page[size]=1";
    }

    private static String valueLookup(String value) {
        return "/api/picklist-values?filter[picklistSourceType][eq]=FIELD&filter[picklistSourceId][eq]="
                + STAGE_ID + "&filter[value][eq]=" + value + "&page[size]=1";
    }

    private void stubJson(String url, int status, String body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    /** An empty tenant: nothing found by any natural key; every POST returns its new id. */
    private void stubEmptyTenant(boolean stageFails) {
        // The display-field step re-resolves the collection, which by then exists.
        wm.stubFor(get(urlEqualTo(COLLECTION_LOOKUP)).inScenario("collection")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}"))
                .willSetStateTo("exists"));
        wm.stubFor(get(urlEqualTo(COLLECTION_LOOKUP)).inScenario("collection")
                .whenScenarioStateIs("exists")
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"" + COLLECTION_ID + "\"}]}")));
        stubJson("/api/collections/" + COLLECTION_ID, 200,
                "{\"data\":{\"id\":\"" + COLLECTION_ID + "\",\"attributes\":{\"name\":\"deals\",\"displayName\":\"Deals\"}}}");
        wm.stubFor(post(urlEqualTo("/api/collections")).willReturn(aResponse().withStatus(201)
                .withBody("{\"data\":{\"id\":\"" + COLLECTION_ID + "\"}}")));
        wm.stubFor(patch(urlEqualTo("/api/collections/" + COLLECTION_ID)).willReturn(aResponse().withStatus(200)
                .withBody("{\"data\":{\"id\":\"" + COLLECTION_ID + "\"}}")));

        stubJson(fieldLookup("title"), 200, "{\"data\":[]}");
        stubJson(fieldLookup("stage"), 200, "{\"data\":[]}");
        wm.stubFor(post(urlEqualTo("/api/fields"))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("title")))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"" + TITLE_ID + "\"}}")));
        wm.stubFor(post(urlEqualTo("/api/fields"))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("stage")))
                .willReturn(stageFails
                        ? aResponse().withStatus(422).withBody(
                                "{\"errors\":[{\"status\":\"422\",\"detail\":\"Field type not allowed\"}]}")
                        : aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"" + STAGE_ID + "\"}}")));

        stubJson(valueLookup("OPEN"), 200, "{\"data\":[]}");
        stubJson(valueLookup("WON"), 200, "{\"data\":[]}");
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("OPEN")))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"" + OPEN_ID + "\"}}")));
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("WON")))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"" + WON_ID + "\"}}")));
    }

    /** The tenant as the first call left it: every record exists with exactly the applied attributes. */
    private void stubExistingTenant() {
        stubJson(COLLECTION_LOOKUP, 200, "{\"data\":[{\"id\":\"" + COLLECTION_ID + "\"}]}");
        stubJson("/api/collections/" + COLLECTION_ID, 200,
                "{\"data\":{\"id\":\"" + COLLECTION_ID + "\",\"attributes\":{\"name\":\"deals\","
                        + "\"displayName\":\"Deals\",\"tenantScoped\":true},"
                        + "\"relationships\":{\"displayFieldId\":{\"data\":{\"type\":\"fields\",\"id\":\""
                        + TITLE_ID + "\"}}}}}");

        stubJson(fieldLookup("title"), 200, "{\"data\":[{\"id\":\"" + TITLE_ID + "\"}]}");
        stubJson("/api/fields/" + TITLE_ID, 200,
                "{\"data\":{\"id\":\"" + TITLE_ID + "\",\"attributes\":{\"name\":\"title\",\"type\":\"STRING\","
                        + "\"required\":true,\"uniqueConstraint\":false},"
                        + "\"relationships\":{\"collectionId\":{\"data\":{\"id\":\"" + COLLECTION_ID + "\"}}}}}");
        stubJson(fieldLookup("stage"), 200, "{\"data\":[{\"id\":\"" + STAGE_ID + "\"}]}");
        stubJson("/api/fields/" + STAGE_ID, 200,
                "{\"data\":{\"id\":\"" + STAGE_ID + "\",\"attributes\":{\"name\":\"stage\",\"type\":\"PICKLIST\","
                        + "\"required\":false},"
                        + "\"relationships\":{\"collectionId\":{\"data\":{\"id\":\"" + COLLECTION_ID + "\"}}}}}");

        stubJson(valueLookup("OPEN"), 200, "{\"data\":[{\"id\":\"" + OPEN_ID + "\"}]}");
        stubJson("/api/picklist-values/" + OPEN_ID, 200,
                "{\"data\":{\"id\":\"" + OPEN_ID + "\",\"attributes\":{\"picklistSourceType\":\"FIELD\","
                        + "\"picklistSourceId\":\"" + STAGE_ID + "\",\"value\":\"OPEN\",\"label\":\"Open\","
                        + "\"sortOrder\":0,\"isActive\":true}}}");
        stubJson(valueLookup("WON"), 200, "{\"data\":[{\"id\":\"" + WON_ID + "\"}]}");
        stubJson("/api/picklist-values/" + WON_ID, 200,
                "{\"data\":{\"id\":\"" + WON_ID + "\",\"attributes\":{\"picklistSourceType\":\"FIELD\","
                        + "\"picklistSourceId\":\"" + STAGE_ID + "\",\"value\":\"WON\",\"label\":\"Won\","
                        + "\"color\":\"#22C55E\",\"sortOrder\":1,\"isActive\":true}}}");

        wm.stubFor(patch(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"data\":{}}")));
    }

    private static JsonNode json(CallToolResult result) {
        return MAPPER.readTree(((TextContent) result.content().get(0)).text());
    }

    private static JsonNode field(JsonNode body, String name) {
        for (JsonNode f : body.path("fields")) {
            if (name.equals(f.path("fieldName").asString())) return f;
        }
        throw new AssertionError("no field result for " + name + " in " + body);
    }

    @Test
    void firstCallOnEmptyTenantCreatesCollectionFieldsValuesAndDisplayField() {
        stubEmptyTenant(false);

        CallToolResult result = tool.apply(args(true, "Won"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        JsonNode body = json(result);
        assertThat(body.path("action").asString()).isEqualTo("created");
        assertThat(body.path("id").asString()).isEqualTo(COLLECTION_ID);
        assertThat(field(body, "title").path("action").asString()).isEqualTo("created");
        assertThat(field(body, "title").path("id").asString()).isEqualTo(TITLE_ID);
        assertThat(field(body, "stage").path("action").asString()).isEqualTo("created");
        assertThat(field(body, "stage").path("values").path("values").get(0).path("action").asString())
                .isEqualTo("created");
        assertThat(body.path("displayField").path("id").asString()).isEqualTo(TITLE_ID);
        assertThat(body.path("displayField").path("action").asString()).isEqualTo("updated");

        wm.verify(1, postRequestedFor(urlEqualTo("/api/collections"))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("deals")))
                .withRequestBody(matchingJsonPath("$.data.attributes.displayName", equalTo("Deals"))));
        wm.verify(1, postRequestedFor(urlEqualTo("/api/fields"))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("title")))
                .withRequestBody(matchingJsonPath("$.data.attributes.collectionId", equalTo(COLLECTION_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.type", equalTo("STRING")))
                .withRequestBody(matchingJsonPath("$.data.attributes.required", equalTo("true"))));
        // Field-scoped picklist: no global binding on the field itself.
        wm.verify(1, postRequestedFor(urlEqualTo("/api/fields"))
                .withRequestBody(matchingJsonPath("$.data.attributes.name", equalTo("stage")))
                .withRequestBody(matchingJsonPath("$.data.attributes.type", equalTo("PICKLIST")))
                .withRequestBody(matchingJsonPath("$.data.attributes.fieldTypeConfig", absent())));
        wm.verify(1, postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceType", equalTo("FIELD")))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceId", equalTo(STAGE_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("OPEN")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("0"))));
        wm.verify(1, postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceId", equalTo(STAGE_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("WON")))
                .withRequestBody(matchingJsonPath("$.data.attributes.color", equalTo("#22C55E"))));
        wm.verify(1, patchRequestedFor(urlEqualTo("/api/collections/" + COLLECTION_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes",
                        equalToJson("{\"displayFieldId\":\"" + TITLE_ID + "\"}"))));
    }

    @Test
    void identicalSecondCallWritesNothingAndReportsUnchanged() {
        Map<String, Object> args = args(true, "Won");
        stubEmptyTenant(false);
        assertThat(json(tool.apply(args)).path("action").asString()).isEqualTo("created");

        wm.resetAll();
        stubExistingTenant();
        CallToolResult second = tool.apply(args);

        assertThat(second.isError()).isNotEqualTo(Boolean.TRUE);
        JsonNode body = json(second);
        assertThat(body.path("action").asString()).isEqualTo("unchanged");
        assertThat(body.has("changed")).isFalse();
        for (String name : List.of("title", "stage")) {
            assertThat(field(body, name).path("action").asString()).as(name).isEqualTo("unchanged");
        }
        for (JsonNode v : field(body, "stage").path("values").path("values")) {
            assertThat(v.path("action").asString()).isEqualTo("unchanged");
        }
        assertThat(body.path("displayField").path("action").asString()).isEqualTo("unchanged");
        wm.verify(0, postRequestedFor(anyUrl()));
        wm.verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void changedRequiredPatchesOnlyThatField() {
        stubExistingTenant();

        JsonNode body = json(tool.apply(args(false, "Won")));

        assertThat(body.path("action").asString()).isEqualTo("updated");
        JsonNode title = field(body, "title");
        assertThat(title.path("action").asString()).isEqualTo("updated");
        assertThat(title.path("changed").toString()).isEqualTo("[\"required\"]");
        assertThat(field(body, "stage").path("action").asString()).isEqualTo("unchanged");
        wm.verify(1, patchRequestedFor(anyUrl()));
        wm.verify(1, patchRequestedFor(urlEqualTo("/api/fields/" + TITLE_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes", equalToJson("{\"required\":false}"))));
        wm.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void changedPicklistLabelPatchesOnlyThatValue() {
        stubExistingTenant();

        JsonNode body = json(tool.apply(args(true, "Closed won")));

        assertThat(body.path("action").asString()).isEqualTo("updated");
        JsonNode stage = field(body, "stage");
        assertThat(stage.path("action").asString()).isEqualTo("unchanged");
        JsonNode won = stage.path("values").path("values").get(1);
        assertThat(won.path("value").asString()).isEqualTo("WON");
        assertThat(won.path("action").asString()).isEqualTo("updated");
        assertThat(won.path("changed").toString()).isEqualTo("[\"label\"]");
        wm.verify(1, patchRequestedFor(anyUrl()));
        wm.verify(1, patchRequestedFor(urlEqualTo("/api/picklist-values/" + WON_ID))
                .withRequestBody(matchingJsonPath("$.data.attributes", equalToJson("{\"label\":\"Closed won\"}"))));
        wm.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void failedFieldIsReportedNextToTheCollectionAndFieldsThatSucceeded() {
        stubEmptyTenant(true);

        CallToolResult result = tool.apply(args(true, "Won"));

        assertThat(result.isError()).isTrue();
        JsonNode body = json(result);
        assertThat(body.path("action").asString()).isEqualTo("created");
        assertThat(body.path("id").asString()).isEqualTo(COLLECTION_ID);
        assertThat(field(body, "title").path("action").asString()).isEqualTo("created");
        JsonNode stage = field(body, "stage");
        assertThat(stage.path("action").asString()).isEqualTo("failed");
        assertThat(stage.path("error").asString()).contains("422").contains("Field type not allowed");
        assertThat(body.path("displayField").path("action").asString()).isEqualTo("updated");
        wm.verify(0, postRequestedFor(urlEqualTo("/api/picklist-values")));
    }

    @Test
    void rejectsInlineValuesOnANonPicklistFieldBeforeWriting() {
        Map<String, Object> args = Map.of("name", "deals", "fields", List.of(Map.of(
                "fieldName", "title", "type", "text",
                "values", List.of(Map.of("value", "A", "label", "A")))));

        CallToolResult result = tool.apply(args);

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().get(0)).text()).contains("title").contains("picklist");
        wm.verify(0, postRequestedFor(anyUrl()));
    }
}
