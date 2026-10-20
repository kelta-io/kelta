package io.kelta.mcp.tool.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ApplyFieldPicklistToolTest {

    private static final String COLLECTION_ID = "c0000000-0000-0000-0000-000000000001";
    private static final String FIELD_ID = "f0000000-0000-0000-0000-000000000001";
    private static final String COLLECTION_LOOKUP = "/api/collections?filter[name][eq]=deals";
    private static final String FIELD_LOOKUP = "/api/fields?filter[collectionId][eq]=" + COLLECTION_ID
            + "&filter[name][eq]=stage&page[size]=1";
    private static final String VALUE_PREFIX = "/api/picklist-values?filter[picklistSourceType][eq]=FIELD"
            + "&filter[picklistSourceId][eq]=" + FIELD_ID;

    private WireMockServer wm;
    private ApplyFieldPicklistTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyFieldPicklistTool(client);
        RequestPatHolder.set("klt_field_picklist");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private void stubField(String type) {
        wm.stubFor(get(urlEqualTo(COLLECTION_LOOKUP)).willReturn(aResponse().withStatus(200).withBody(
                "{\"data\":[{\"type\":\"collections\",\"id\":\"" + COLLECTION_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo(FIELD_LOOKUP)).willReturn(aResponse().withStatus(200).withBody(
                "{\"data\":[{\"type\":\"fields\",\"id\":\"" + FIELD_ID + "\","
                        + "\"attributes\":{\"name\":\"stage\",\"type\":\"" + type + "\"}}]}")));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void createsFieldScopedValuesKeyedOnTheFieldId() {
        stubField("PICKLIST");
        wm.stubFor(get(urlEqualTo(VALUE_PREFIX + "&filter[value][eq]=OPEN&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(get(urlEqualTo(VALUE_PREFIX + "&filter[value][eq]=WON&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        wm.stubFor(post(urlEqualTo("/api/picklist-values"))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"v1\"}}")));

        CallToolResult result = tool.apply(Map.of(
                "collectionName", "deals",
                "fieldName", "stage",
                "values", List.of(
                        Map.of("value", "OPEN", "label", "Open", "color", "#22C55E"),
                        Map.of("value", "WON", "label", "Won"))));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("\"fieldId\":\"" + FIELD_ID + "\"").contains("\"action\":\"created\"");
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withHeader("Authorization", equalTo("Bearer klt_field_picklist"))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceType", equalTo("FIELD")))
                .withRequestBody(matchingJsonPath("$.data.attributes.picklistSourceId", equalTo(FIELD_ID)))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("OPEN")))
                .withRequestBody(matchingJsonPath("$.data.attributes.color", equalTo("#22C55E")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("0"))));
        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/picklist-values"))
                .withRequestBody(matchingJsonPath("$.data.attributes.value", equalTo("WON")))
                .withRequestBody(matchingJsonPath("$.data.attributes.sortOrder", equalTo("1"))));
    }

    @Test
    void pruneDeactivatesTheFieldsOtherActiveValues() {
        stubField("PICKLIST");
        wm.stubFor(get(urlEqualTo(VALUE_PREFIX + "&filter[value][eq]=OPEN&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"v-open\",\"attributes\":{\"value\":\"OPEN\"}}]}")));
        wm.stubFor(get(urlEqualTo("/api/picklist-values/v-open")).willReturn(aResponse().withStatus(200).withBody(
                "{\"data\":{\"id\":\"v-open\",\"attributes\":{\"value\":\"OPEN\",\"label\":\"Open\",\"sortOrder\":0}}}")));
        wm.stubFor(get(urlEqualTo(VALUE_PREFIX + "&filter[isActive][eq]=true&page[size]=200"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"v-open\",\"attributes\":{\"value\":\"OPEN\"}},"
                                + "{\"id\":\"v-old\",\"attributes\":{\"value\":\"OLD\"}}]}")));
        wm.stubFor(patch(urlEqualTo("/api/picklist-values/v-old"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"v-old\"}}")));

        CallToolResult result = tool.apply(Map.of(
                "collectionName", "deals",
                "fieldName", "stage",
                "values", List.of(Map.of("value", "OPEN", "label", "Open")),
                "prune", true));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("\"action\":\"unchanged\"").contains("\"pruned\":[\"OLD\"]");
        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/picklist-values/v-old"))
                .withRequestBody(matchingJsonPath("$.data.attributes.isActive", equalTo("false"))));
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/picklist-values")));
    }

    @Test
    void rejectsAFieldThatIsNotAPicklist() {
        stubField("STRING");

        CallToolResult result = tool.apply(Map.of(
                "collectionName", "deals",
                "fieldName", "stage",
                "values", List.of(Map.of("value", "OPEN", "label", "Open"))));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("not a picklist field");
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/picklist-values")));
    }

    @Test
    void rejectsAMissingField() {
        wm.stubFor(get(urlEqualTo(COLLECTION_LOOKUP)).willReturn(aResponse().withStatus(200).withBody(
                "{\"data\":[{\"type\":\"collections\",\"id\":\"" + COLLECTION_ID + "\"}]}")));
        wm.stubFor(get(urlEqualTo(FIELD_LOOKUP))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        CallToolResult result = tool.apply(Map.of(
                "collectionName", "deals",
                "fieldName", "stage",
                "values", List.of(Map.of("value", "OPEN", "label", "Open"))));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("No field \"stage\"");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThat(tool.apply(Map.of("values", List.of(Map.of("value", "A", "label", "A")))).isError())
                .isEqualTo(Boolean.TRUE);
        assertThat(tool.apply(Map.of("collectionName", "deals", "fieldName", "stage", "values", List.of())).isError())
                .isEqualTo(Boolean.TRUE);
        assertThat(tool.apply(Map.of("collectionName", "deals", "fieldName", "stage",
                "values", List.of(Map.of("value", "A")))).isError()).isEqualTo(Boolean.TRUE);
    }
}
