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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * apply_page is keyed on {@code path} (via {@link AdminLookups#idByNaturalKey}, falling back to
 * {@code slug} when path itself doesn't match), diffed via {@link AdminLookups#readAttributes} +
 * {@link AdminLookups#diff}. {@code config} is dry-run through {@code POST
 * /api/ui-pages/validate} before any create/update — these tests stub that endpoint explicitly
 * rather than re-deriving {@code UiPageConfigValidator}'s own checks.
 */
class ApplyPageToolTest {

    private static final String PAGE_ID = "P1";

    private WireMockServer wm;
    private ApplyPageTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        GatewayHttpClient client = new GatewayHttpClient(
                RestClient.builder(),
                new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
        tool = new ApplyPageTool(client);
        RequestPatHolder.set("klt_apply_page_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
        wm.stop();
    }

    private CallToolResult call(Map<String, Object> args) {
        return tool.apply(args);
    }

    @Test
    void rejectsWithoutName() {
        CallToolResult result = call(Map.of("path", "/accounts"));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsWithoutPath() {
        CallToolResult result = call(Map.of("name", "Accounts"));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void descriptionIncludesMinimalHeadingAndRepeaterExample() {
        String description = tool.toSpecification().tool().description();
        assertThat(description)
                .contains("\"type\":\"heading\"")
                .contains("\"type\":\"repeater\"")
                .contains("dataSources");
    }

    /** New page — validated, then created; nothing exists at the path yet. */
    @Test
    void createsNewPage() {
        stubNoExistingByPath("/accounts");
        stubValidate(true, List.of());
        stubCreatePage(PAGE_ID);

        CallToolResult result = call(Map.of("name", "Accounts", "path", "/accounts", "config", Map.of()));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"created\",\"id\":\"" + PAGE_ID + "\"");

        wm.verify(WireMock.postRequestedFor(urlEqualTo("/api/ui-pages"))
                .withRequestBody(WireMock.matchingJsonPath("$.data.attributes.name", WireMock.equalTo("Accounts")))
                .withRequestBody(WireMock.matchingJsonPath("$.data.attributes.path", WireMock.equalTo("/accounts"))));
    }

    /** Re-applying the identical page reports unchanged and issues no write. */
    @Test
    void reappliesIdenticalPageAsUnchanged() {
        stubExistingByPath("/accounts", PAGE_ID);
        stubValidate(true, List.of());
        stubReadPage(PAGE_ID, Map.of("name", "Accounts", "path", "/accounts", "config", Map.of()));

        CallToolResult result = call(Map.of("name", "Accounts", "path", "/accounts", "config", Map.of()));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).isEqualTo("{\"action\":\"unchanged\",\"id\":\"" + PAGE_ID + "\"}");

        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/ui-pages")));
        wm.verify(0, WireMock.patchRequestedFor(urlMatching("/api/ui-pages/.*")));
    }

    /** Changing only config reports updated with changed:["config"], patching just that key. */
    @Test
    void changingConfigReportsUpdatedWithChangedConfigOnly() {
        stubExistingByPath("/accounts", PAGE_ID);
        stubValidate(true, List.of());
        stubReadPage(PAGE_ID, Map.of("name", "Accounts", "path", "/accounts", "config", Map.of()));
        wm.stubFor(patch(urlEqualTo("/api/ui-pages/" + PAGE_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + PAGE_ID + "\"}}")));

        Map<String, Object> newConfig = Map.of("schemaVersion", 2);
        CallToolResult result = call(Map.of("name", "Accounts", "path", "/accounts", "config", newConfig));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"updated\",\"id\":\"" + PAGE_ID + "\"");
        assertThat(text).contains("\"changed\":[\"config\"]");

        wm.verify(WireMock.patchRequestedFor(urlEqualTo("/api/ui-pages/" + PAGE_ID))
                .withRequestBody(WireMock.matchingJsonPath("$.data.attributes.config.schemaVersion", WireMock.equalTo("2")))
                .withRequestBody(WireMock.notMatching(".*\"name\".*")));
    }

    /**
     * An unknown widget type fails validation server-side (UiPageConfigValidator, KLT-217) — the
     * tool maps the returned JSON Pointer onto the structured error and writes no row at all (no
     * lookup, create, or patch call is even made).
     */
    @Test
    void rejectsInvalidConfigWithStructuredErrorAndWritesNothing() {
        stubValidate(false, List.of(Map.of(
                "path", "/components/0/type",
                "message", "Unknown widget type 'bogus'; GET /api/pages/widgets lists the built-in catalogue",
                "severity", "error")));

        CallToolResult result = call(Map.of(
                "name", "Accounts", "path", "/accounts",
                "config", Map.of("components", List.of(Map.of("id", "c1", "type", "bogus")))));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured.get("status")).isEqualTo(400);
        @SuppressWarnings("unchecked")
        List<Object> errors = (List<Object>) structured.get("errors");
        assertThat(errors).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstError = (Map<String, Object>) errors.get(0);
        assertThat(firstError.get("source")).isEqualTo(Map.of("pointer", "/components/0/type"));

        wm.verify(0, WireMock.getRequestedFor(urlMatching("/api/ui-pages\\?.*")));
        wm.verify(0, WireMock.postRequestedFor(urlEqualTo("/api/ui-pages")));
        wm.verify(0, WireMock.patchRequestedFor(urlMatching("/api/ui-pages/.*")));
    }

    /** path changed but slug is the same page's identity — matches by slug and updates in place. */
    @Test
    void fallsBackToSlugWhenPathDoesNotMatchAnExistingRow() {
        stubNoExistingByPath("/accounts-v2");
        wm.stubFor(get(urlEqualTo("/api/ui-pages?filter[slug][eq]=accounts&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + PAGE_ID + "\"}]}")));
        stubValidate(true, List.of());
        stubReadPage(PAGE_ID, Map.of("name", "Accounts", "path", "/accounts", "slug", "accounts", "config", Map.of()));
        wm.stubFor(patch(urlEqualTo("/api/ui-pages/" + PAGE_ID))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":{\"id\":\"" + PAGE_ID + "\"}}")));

        CallToolResult result = call(Map.of(
                "name", "Accounts", "path", "/accounts-v2", "slug", "accounts", "config", Map.of()));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"action\":\"updated\",\"id\":\"" + PAGE_ID + "\"");
        assertThat(text).contains("\"changed\":[\"path\"]");
    }

    // =========================================================================
    // Stub helpers
    // =========================================================================

    private void stubNoExistingByPath(String path) {
        wm.stubFor(get(urlEqualTo("/api/ui-pages?filter[path][eq]="
                        + URLEncoder.encode(path, StandardCharsets.UTF_8) + "&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
    }

    private void stubExistingByPath(String path, String id) {
        wm.stubFor(get(urlEqualTo("/api/ui-pages?filter[path][eq]="
                        + URLEncoder.encode(path, StandardCharsets.UTF_8) + "&page[size]=1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"" + id + "\"}]}")));
    }

    private void stubCreatePage(String id) {
        wm.stubFor(post(urlEqualTo("/api/ui-pages"))
                .willReturn(aResponse().withStatus(201).withBody(
                        "{\"data\":{\"id\":\"" + id + "\"}}")));
    }

    private void stubReadPage(String id, Map<String, Object> attributes) {
        StringBuilder attrs = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (!first) attrs.append(',');
            first = false;
            attrs.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> m) {
                attrs.append(m.isEmpty() ? "{}" : m.toString());
            } else {
                attrs.append('"').append(value).append('"');
            }
        }
        attrs.append('}');
        wm.stubFor(get(urlEqualTo("/api/ui-pages/" + id))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":{\"id\":\"" + id + "\",\"attributes\":" + attrs + "}}")));
    }

    /**
     * Stubs {@code POST /api/ui-pages/validate} — {@code errors} entries carry
     * {@code path}/{@code message}/{@code severity}, matching {@code UiPageValidateController}'s
     * {@code {valid, errors:[{path, message, severity}]}} shape.
     */
    private void stubValidate(boolean valid, List<Map<String, Object>> errors) {
        StringBuilder errorsJson = new StringBuilder("[");
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) errorsJson.append(',');
            Map<String, Object> e = errors.get(i);
            errorsJson.append("{\"path\":\"").append(e.get("path"))
                    .append("\",\"message\":\"").append(e.get("message"))
                    .append("\",\"severity\":\"").append(e.get("severity"))
                    .append("\"}");
        }
        errorsJson.append(']');
        String body = "{\"valid\":" + valid + ",\"errors\":" + errorsJson + "}";
        wm.stubFor(post(urlEqualTo("/api/ui-pages/validate"))
                .willReturn(aResponse().withStatus(200).withBody(body)));
    }
}
