package io.kelta.mcp;

import io.kelta.mcp.resource.AdminResource;
import io.kelta.mcp.resource.UserResource;
import io.kelta.mcp.resource.UserResourceTemplate;
import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.UserTool;
import io.kelta.mcp.transport.HttpServletStatelessServerTransportProvider;
import io.kelta.mcp.transport.KeltaMcpController;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the application context starts and both MCP server instances
 * are wired with their respective servlet registrations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "kelta.mcp.gateway-url=http://localhost:9999",
        "kelta.mcp.session-ttl-minutes=5",
        "kelta.mcp.tool-timeout-ms=10000"
})
class McpApplicationTest {

    @Autowired
    @Qualifier("userMcpServer")
    private McpStatelessSyncServer userServer;

    @Autowired
    @Qualifier("adminMcpServer")
    private McpStatelessSyncServer adminServer;

    @Autowired
    private KeltaMcpController mcpController;

    @Autowired
    private List<UserTool> userTools;

    @Autowired
    private List<UserResource> userResources;

    @Autowired
    private List<UserResourceTemplate> userResourceTemplates;

    @Autowired
    private List<AdminTool> adminTools;

    @Autowired
    private List<AdminResource> adminResources;

    @Autowired
    @Qualifier("userTransportProvider")
    private HttpServletStatelessServerTransportProvider userTransport;

    @Autowired
    @Qualifier("adminTransportProvider")
    private HttpServletStatelessServerTransportProvider adminTransport;

    @Test
    void bothMcpServersAreWired() {
        assertThat(userServer).isNotNull();
        assertThat(adminServer).isNotNull();
        assertThat(userServer).isNotSameAs(adminServer);
    }

    @Test
    void mcpControllerIsRegisteredAtSlugPathPattern() {
        // The Spring MVC controller dispatches /{tenantSlug}/mcp/(user|admin)
        // to the matching SDK transport via @PathVariable.
        assertThat(mcpController).isNotNull();
    }

    @Test
    void allUserToolsAreDiscovered() {
        List<String> names = userTools.stream()
                .map(t -> t.toSpecification().tool().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                "list_collections",
                "get_collection_schema",
                "query_collection",
                "get_record",
                "search",
                "semantic_search",
                "describe_api",
                "create_record",
                "update_record",
                "delete_record",
                "bulk_apply",
                "execute_flow",
                "get_flow_run",
                "submit_for_approval",
                "list_approvals"
        );
    }

    @Test
    void userResourcesAreDiscovered() {
        List<String> uris = userResources.stream()
                .map(r -> r.toSpecification().resource().uri())
                .toList();
        assertThat(uris).containsExactlyInAnyOrder(
                "kelta://collections",
                "kelta://openapi.json",
                "kelta://docs/jsonapi",
                "kelta://docs/page-layouts",
                "kelta://docs/list-views",
                "kelta://docs/dashboards",
                "kelta://docs/ui-pages",
                "kelta://docs/ui-menus"
        );
    }

    @Test
    void adminResourcesAreDiscovered() {
        // Admin has no schema/openapi browse resources (those are data-plane), but the
        // authoring docs are shared — same DocResource beans implement both marker
        // interfaces, so both endpoints see kelta://docs/<topic>.
        List<String> uris = adminResources.stream()
                .map(r -> r.toSpecification().resource().uri())
                .toList();
        assertThat(uris).containsExactlyInAnyOrder(
                "kelta://docs/jsonapi",
                "kelta://docs/page-layouts",
                "kelta://docs/list-views",
                "kelta://docs/dashboards",
                "kelta://docs/ui-pages",
                "kelta://docs/ui-menus"
        );
    }

    @Test
    void userResourceTemplatesAreDiscovered() {
        List<String> templates = userResourceTemplates.stream()
                .map(t -> t.toSpecification().resourceTemplate().uriTemplate())
                .toList();
        assertThat(templates).containsExactly("kelta://collections/{name}");
    }

    @Test
    void adminEndpointSurfaceCoversPhase6Through8() {
        List<String> names = adminTools.stream()
                .map(t -> t.toSpecification().tool().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                // shared read-only browse tools (also on /mcp/user)
                "list_collections",
                "get_collection_schema",
                // schema admin (Phase 6)
                "create_collection",
                "update_collection",
                "delete_collection",
                "add_field",
                "update_field",
                "remove_field",
                "create_validation_rule",
                "list_validation_rules",
                "update_validation_rule",
                "delete_validation_rule",
                "create_unique_constraint",
                "list_unique_constraints",
                "delete_unique_constraint",
                "create_picklist",
                "apply_picklist",
                "list_picklists",
                "get_picklist",
                "delete_picklist",
                "add_picklist_value",
                "update_picklist_value",
                "deactivate_picklist_value",
                // UI admin (Phase 7)
                "apply_layout",
                "create_layout",
                "update_layout",
                "delete_layout",
                "create_listview",
                "apply_listview",
                "list_listviews",
                "update_listview",
                "delete_listview",
                "apply_menu",
                "apply_dashboard",
                // automation admin + integrations (Phase 8)
                "create_flow",
                "update_flow",
                "delete_flow",
                "list_flows",
                "import_api_spec",
                "materialize_api_collection");
    }

    @Test
    void everyToolHasTitleAndAnnotations() {
        // Every tool, user-side and admin-side, must publish a friendly title
        // and the standard MCP safety hints. This pushes clients toward the
        // right UX (preload safe reads, confirm destructive writes) and prevents
        // a future tool from shipping with a blank annotations record.
        List<Tool> all = java.util.stream.Stream.concat(
                        userTools.stream().map(t -> t.toSpecification().tool()),
                        adminTools.stream().map(t -> t.toSpecification().tool()))
                .toList();

        for (Tool t : all) {
            assertThat(t.title())
                    .as("tool %s must have a friendly title", t.name())
                    .isNotBlank();
            ToolAnnotations a = t.annotations();
            assertThat(a)
                    .as("tool %s must declare annotations", t.name())
                    .isNotNull();
            assertThat(a.openWorldHint())
                    .as("tool %s must mark openWorldHint=true (calls the gateway)", t.name())
                    .isTrue();
            assertThat(a.readOnlyHint())
                    .as("tool %s must declare readOnlyHint", t.name())
                    .isNotNull();
        }
    }

    @Test
    void readToolsAreMarkedReadOnly() {
        // Pinned by name so a future tool that switches kind without updating
        // its hints fails this test loud and clear instead of silently
        // misleading the client.
        Set<String> readOnlyTools = Set.of(
                "list_collections", "get_collection_schema", "query_collection",
                "get_record", "search", "describe_api", "get_flow_run",
                "list_approvals");
        for (UserTool ut : userTools) {
            Tool t = ut.toSpecification().tool();
            if (!readOnlyTools.contains(t.name())) continue;
            assertThat(t.annotations().readOnlyHint())
                    .as("user tool %s should be read-only", t.name())
                    .isTrue();
            assertThat(t.annotations().idempotentHint())
                    .as("user tool %s should be idempotent (read-only is always idempotent)", t.name())
                    .isTrue();
        }
        // Admin-side reads: the shared browse tools plus the picklist/validation/constraint/flow/listview read tools.
        Set<String> adminReads = Set.of(
                "list_collections", "get_collection_schema",
                "list_picklists", "get_picklist",
                "list_validation_rules", "list_unique_constraints",
                "list_flows", "list_listviews");
        for (AdminTool at : adminTools) {
            Tool t = at.toSpecification().tool();
            if (!adminReads.contains(t.name())) continue;
            assertThat(t.annotations().readOnlyHint())
                    .as("admin tool %s should be read-only", t.name())
                    .isTrue();
            assertThat(t.annotations().idempotentHint())
                    .as("admin tool %s should be idempotent", t.name())
                    .isTrue();
        }
    }

    @Test
    void destructiveWritesAreMarkedDestructive() {
        // Hard pin: tools that delete or overwrite must surface destructiveHint=true
        // so clients can prompt for confirmation. Adding a destructive tool
        // without flagging it here is a real safety regression — fail loud.
        Set<String> destructive = Set.of(
                "update_record", "delete_record", "bulk_apply",
                "update_collection", "delete_collection",
                "update_field", "remove_field",
                "update_layout", "delete_layout", "update_flow", "delete_flow",
                "delete_picklist",
                "update_picklist_value", "deactivate_picklist_value",
                "update_validation_rule", "delete_validation_rule",
                "delete_unique_constraint",
                "update_listview", "delete_listview", "apply_listview", "apply_picklist",
                "apply_menu");
        for (UserTool ut : userTools) {
            Tool t = ut.toSpecification().tool();
            if (!destructive.contains(t.name())) continue;
            assertThat(t.annotations().readOnlyHint()).as("%s readOnlyHint=false", t.name()).isFalse();
            assertThat(t.annotations().destructiveHint()).as("%s destructiveHint=true", t.name()).isTrue();
        }
        for (AdminTool at : adminTools) {
            Tool t = at.toSpecification().tool();
            if (!destructive.contains(t.name())) continue;
            assertThat(t.annotations().readOnlyHint()).as("%s readOnlyHint=false", t.name()).isFalse();
            assertThat(t.annotations().destructiveHint()).as("%s destructiveHint=true", t.name()).isTrue();
        }
    }

    @Test
    void userServerSendsOrientingInstructionsOnInitialize() throws Exception {
        // The `instructions` field of the initialize response is the one
        // place a client is oriented before making any tool call — assert
        // it's actually populated (not left as the SDK default of null/blank)
        // and mentions the discovery path a fresh client needs.
        MockHttpServletResponse res = initialize(userTransport, "/threadline-clothing/mcp/user");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString();
        assertThat(body).contains("list_collections").contains("get_collection_schema");
    }

    @Test
    void adminServerSendsOrientingInstructionsOnInitialize() throws Exception {
        MockHttpServletResponse res = initialize(adminTransport, "/threadline-clothing/mcp/admin");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString();
        assertThat(body).contains("create_collection").contains("add_field");
    }

    private static MockHttpServletResponse initialize(
            HttpServletStatelessServerTransportProvider transport, String path) throws Exception {
        MockHttpServletResponse res = rpc(transport, path, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25",
                  "capabilities":{},
                  "clientInfo":{"name":"test-client","version":"1.0"}
                }}""");
        return res;
    }

    private static MockHttpServletResponse rpc(
            HttpServletStatelessServerTransportProvider transport, String path, String jsonRpcBody)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.addHeader("Content-Type", "application/json");
        req.setContent(jsonRpcBody.getBytes());
        MockHttpServletResponse res = new MockHttpServletResponse();
        transport.service(req, res);
        return res;
    }

    @Test
    void userResourcesListIncludesAuthoringDocs() throws Exception {
        MockHttpServletResponse res = rpc(userTransport, "/threadline-clothing/mcp/user",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\",\"params\":{}}");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).contains("kelta://docs/list-views");
    }

    @Test
    void adminResourcesListIncludesAuthoringDocs() throws Exception {
        MockHttpServletResponse res = rpc(adminTransport, "/threadline-clothing/mcp/admin",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\",\"params\":{}}");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).contains("kelta://docs/list-views");
    }

    @Test
    void userResourcesReadReturnsTheDocBody() throws Exception {
        MockHttpServletResponse res = rpc(userTransport, "/threadline-clothing/mcp/user",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\","
                        + "\"params\":{\"uri\":\"kelta://docs/list-views\"}}");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString())
                .contains("rowLimit")
                .contains("{10, 25, 50, 100}");
    }

    @Test
    void adminResourcesReadReturnsTheDocBody() throws Exception {
        MockHttpServletResponse res = rpc(adminTransport, "/threadline-clothing/mcp/admin",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\","
                        + "\"params\":{\"uri\":\"kelta://docs/list-views\"}}");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString())
                .contains("rowLimit")
                .contains("{10, 25, 50, 100}");
    }

    @Test
    void mutationToolsNeverLeakToAdminEndpoint() {
        List<String> adminNames = adminTools.stream()
                .map(t -> t.toSpecification().tool().name())
                .toList();
        // Hard guarantee: no write-side user tool can appear on the admin server.
        assertThat(adminNames).doesNotContain(
                "create_record", "update_record", "delete_record", "bulk_apply",
                "execute_flow", "submit_for_approval", "list_approvals");
    }

    @Test
    void everyCreateToolHasDeleteCounterpart() {
        // Asserts the ADMIN_INSTRUCTIONS claim: "delete_ counterparts exist for every
        // create_ tool". Adding a create_ tool without a delete_ counterpart fails here.
        Set<String> adminNames = adminTools.stream()
                .map(t -> t.toSpecification().tool().name())
                .collect(java.util.stream.Collectors.toSet());
        for (String name : adminNames) {
            if (!name.startsWith("create_")) continue;
            String deleteCounterpart = "delete_" + name.substring("create_".length());
            assertThat(adminNames)
                    .as("create_ tool '%s' must have a '%s' counterpart", name, deleteCounterpart)
                    .contains(deleteCounterpart);
        }
    }
}
