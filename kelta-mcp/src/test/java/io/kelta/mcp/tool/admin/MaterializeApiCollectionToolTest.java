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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class MaterializeApiCollectionToolTest {

    private static final String PATH = "/api/api-specs/spec-1/operations/get_pets/materialize";

    private WireMockServer wm;
    private MaterializeApiCollectionTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new MaterializeApiCollectionTool(client);
        RequestPatHolder.set("klt_materialize_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.toSpecification().callHandler().apply(
                null, new CallToolRequest("materialize_api_collection", args, null));
    }

    @Test
    void postsToTheOperationMaterializeRoute() {
        wm.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(201).withBody("{\"data\":{\"id\":\"c1\"}}")));

        CallToolResult result = call(Map.of(
                "specId", "spec-1",
                "operationId", "get_pets",
                "collectionName", "pets",
                "displayName", "Pets",
                "dataPath", "$.items",
                "idAttribute", "petId",
                "credentialRef", "petstore-key"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        wm.verify(1, WireMock.postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.collectionName", equalTo("pets")))
                .withRequestBody(matchingJsonPath("$.displayName", equalTo("Pets")))
                .withRequestBody(matchingJsonPath("$.dataPath", equalTo("$.items")))
                .withRequestBody(matchingJsonPath("$.idAttribute", equalTo("petId")))
                .withRequestBody(matchingJsonPath("$.credentialRef", equalTo("petstore-key"))));
    }

    @Test
    void omitsBlankOptionalArguments() {
        wm.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        call(Map.of(
                "specId", "spec-1",
                "operationId", "get_pets",
                "collectionName", "pets",
                "displayName", " "));

        wm.verify(1, WireMock.postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.collectionName", equalTo("pets")))
                .withRequestBody(matchingJsonPath("$.displayName", absent()))
                .withRequestBody(matchingJsonPath("$.dataPath", absent()))
                .withRequestBody(matchingJsonPath("$.idAttribute", absent()))
                .withRequestBody(matchingJsonPath("$.credentialRef", absent())));
    }

    @Test
    void requiresSpecOperationAndCollectionName() {
        CallToolResult result = call(Map.of("specId", "spec-1", "operationId", "get_pets"));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }
}
