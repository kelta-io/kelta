package io.kelta.mcp.config;

import io.kelta.mcp.auth.KeltaTransportContextExtractor;
import io.kelta.mcp.observe.ObservedToolDecorator;
import io.kelta.mcp.observe.PatPropagatingToolDecorator;
import io.kelta.mcp.observe.RateLimitedToolDecorator;
import io.kelta.mcp.resource.AdminResource;
import io.kelta.mcp.resource.UserResource;
import io.kelta.mcp.resource.UserResourceTemplate;
import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.kelta.mcp.tool.UserTool;
import io.kelta.mcp.transport.HttpServletStatelessServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Wires two independent MCP server instances inside a single Spring Boot
 * process — one mounted at {@code /mcp/user} for data-plane work, one at
 * {@code /mcp/admin} for control-plane work.
 *
 * <p>Each endpoint has its own transport provider, its own tool registry,
 * and its own resource registry. Tools registered on one server are NOT
 * visible from the other — this is the structural guarantee that prevents
 * an admin tool from being callable through the user endpoint.
 *
 * <p><b>Transport mode: stateless.</b> We use the SDK's
 * {@code McpStatelessSyncServer} + a custom
 * {@link HttpServletStatelessServerTransportProvider} adapter. The earlier
 * streamable transport kept per-pod session state in memory, so every pod
 * restart wiped the session map and the next request from a connected
 * client returned {@code -32603 Session not found}. Stateless mode avoids
 * the problem by construction — every request carries everything it needs
 * (PAT in {@code Authorization}, tenant slug in the URL), so pod restarts
 * are invisible to clients.
 *
 * <p>Trade-off: server-initiated SSE notifications ({@code listChanged},
 * resource subscriptions) aren't available in stateless mode. We don't use
 * those today and aren't planning to.
 */
@Configuration
@EnableScheduling
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);
    private static final String SERVER_NAME = "kelta-mcp";
    private static final String SERVER_VERSION = "1.0.0";

    /**
     * Sent once, in the {@code initialize} response, before any tool call —
     * the one place a client (or a human reading the connection handshake)
     * gets oriented instead of reconstructing the operating model tool-by-tool
     * every session. Keep this in sync with the tool surface: update it in
     * the same PR that adds/removes/renames a tool. See kelta-mcp/CLAUDE.md.
     */
    private static final String USER_INSTRUCTIONS = """
            Kelta data-plane MCP server: CRUD records, run flows, approvals, and search \
            over the current tenant's collections.

            Discovery order: list_collections -> get_collection_schema (field names + \
            types for one collection) -> query_collection / get_record.

            Reading: query_collection lists/filters (ops: EQ NEQ GT GTE LT LTE CONTAINS \
            STARTS ENDS ICONTAINS ISTARTS IENDS IEQ ISNULL IN; IN takes a CSV string or \
            array for a multi-value match; fields AND together, NO OR — run multiple \
            queries and union client-side for OR). get_record fetches one row by id. \
            search is keyword full-text; semantic_search is vector/meaning-based. \
            describe_api returns the full OpenAPI 3.0 spec for exact request/response shapes, \
            but only covers plain collection CRUD — flows, approvals, and bulk are NOT in it; \
            use the dedicated tools below for those.

            Writing: create_record / update_record / delete_record / bulk_apply. Check each \
            tool's annotations (readOnlyHint / destructiveHint / idempotentHint) before \
            assuming a call is safe to retry — destructive writes are flagged so a caller \
            can confirm before running them.

            Automation: execute_flow starts a flow asynchronously (not idempotent — each \
            call starts a new run); poll get_flow_run with the returned execution id for \
            status. submit_for_approval / list_approvals drive approval-gated records.

            Conventions: tool arguments are friendly camelCase; the tool boundary translates \
            them into the platform's native JSON:API shape (kebab-case collection/attribute \
            names on the wire) — pass names as given in list_collections / \
            get_collection_schema, don't pre-convert casing yourself.

            Resources: kelta://docs/<topic> (topics: jsonapi, page-layouts, list-views, \
            dashboards, ui-pages, ui-menus) are reference docs for authoring tenant metadata \
            — read one before writing filters, layouts, dashboards, or page config by hand.""";

    private static final String ADMIN_INSTRUCTIONS = """
            Kelta control-plane MCP server: define collections, fields, layouts, flows, \
            picklists, and validation rules for the current tenant.

            Typical build order for a new object: create_collection (optionally with an \
            inline initial field set) -> add_field (repeat per additional field) -> \
            create_validation_rule / create_unique_constraint as needed -> for picklist \
            fields, create_picklist + add_picklist_value first, then reference it from \
            add_field via picklistSourceId -> create_layout / create_listview for the admin \
            UI -> create_flow for automation. delete_ counterparts exist for every create_ \
            tool; update_ counterparts exist for collection, flow, layout, listview, and \
            validation_rule.

            Field types: add_field's `type` argument accepts friendly aliases (text, number, \
            picklist, reference, ...) that map onto the native uppercase FieldType enum — see \
            that tool's own description for the full alias table. Every uppercase enum name is \
            also accepted verbatim.

            Validation rules: create_validation_rule's errorConditionFormula is an ERROR \
            condition — the record is REJECTED when it evaluates TRUE (not a must-hold \
            condition). Read the tool description before writing a formula; getting this \
            backwards silently inverts the rule.

            Shared read tools (also callable from /mcp/user): list_collections, \
            get_collection_schema — use these to look up ids/names before a create_/update_ \
            call rather than guessing.

            Bringing in external data: import_api_spec + materialize_api_collection wire up \
            an API-backed collection from an OpenAPI spec.

            Every mutating tool declares destructiveHint / idempotentHint in its annotations \
            — check them before retrying a failed call or assuming a create_ is safe to repeat.

            Resources: kelta://docs/<topic> (topics: jsonapi, page-layouts, list-views, \
            dashboards, ui-pages, ui-menus) are reference docs for authoring layouts, list \
            views, dashboards, and page config directly through create_layout/create_listview/ \
            record attributes — read one before hand-writing a config JSON blob.""";

    @Bean(name = "userTransportProvider")
    public HttpServletStatelessServerTransportProvider userTransportProvider() {
        return new HttpServletStatelessServerTransportProvider(
                new KeltaTransportContextExtractor());
    }

    @Bean(name = "adminTransportProvider")
    public HttpServletStatelessServerTransportProvider adminTransportProvider() {
        return new HttpServletStatelessServerTransportProvider(
                new KeltaTransportContextExtractor());
    }

    @Bean(name = "userMcpServer")
    public McpStatelessSyncServer userMcpServer(
            @org.springframework.beans.factory.annotation.Qualifier("userTransportProvider")
            HttpServletStatelessServerTransportProvider transport,
            List<UserTool> userTools,
            List<UserResource> userResources,
            List<UserResourceTemplate> userResourceTemplates,
            ObservedToolDecorator decorator,
            RateLimitedToolDecorator rateLimiter,
            PatPropagatingToolDecorator patPropagator) {
        McpStatelessSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME + "-user", SERVER_VERSION)
                .instructions(USER_INSTRUCTIONS)
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .resources(false, false)  // (subscribe, listChanged): both off in stateless
                        .build())
                .build();
        server.addTool(wrap(pingTool("user"), "user", decorator, rateLimiter, patPropagator));
        for (UserTool tool : userTools) {
            McpStatelessServerFeatures.SyncToolSpecification spec = wrap(
                    tool.toSpecification(), "user", decorator, rateLimiter, patPropagator);
            server.addTool(spec);
            log.info("Registered user tool: {}", spec.tool().name());
        }
        for (UserResource resource : userResources) {
            McpStatelessServerFeatures.SyncResourceSpecification spec = resource.toSpecification();
            server.addResource(spec);
            log.info("Registered user resource: {}", spec.resource().uri());
        }
        for (UserResourceTemplate template : userResourceTemplates) {
            McpStatelessServerFeatures.SyncResourceTemplateSpecification spec = template.toSpecification();
            server.addResourceTemplate(spec);
            log.info("Registered user resource template: {}", spec.resourceTemplate().uriTemplate());
        }
        return server;
    }

    @Bean(name = "adminMcpServer")
    public McpStatelessSyncServer adminMcpServer(
            @org.springframework.beans.factory.annotation.Qualifier("adminTransportProvider")
            HttpServletStatelessServerTransportProvider transport,
            List<AdminTool> adminTools,
            List<AdminResource> adminResources,
            ObservedToolDecorator decorator,
            RateLimitedToolDecorator rateLimiter,
            PatPropagatingToolDecorator patPropagator) {
        McpStatelessSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME + "-admin", SERVER_VERSION)
                .instructions(ADMIN_INSTRUCTIONS)
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .resources(false, false)  // (subscribe, listChanged): both off in stateless
                        .build())
                .build();
        server.addTool(wrap(pingTool("admin"), "admin", decorator, rateLimiter, patPropagator));
        for (AdminTool tool : adminTools) {
            McpStatelessServerFeatures.SyncToolSpecification spec = wrap(
                    tool.toSpecification(), "admin", decorator, rateLimiter, patPropagator);
            server.addTool(spec);
            log.info("Registered admin tool: {}", spec.tool().name());
        }
        for (AdminResource resource : adminResources) {
            McpStatelessServerFeatures.SyncResourceSpecification spec = resource.toSpecification();
            server.addResource(spec);
            log.info("Registered admin resource: {}", spec.resource().uri());
        }
        return server;
    }

    /**
     * Decorator stack, outermost → innermost:
     *  1. PAT propagator — copies the PAT from McpTransportContext into
     *     the per-thread RequestPatHolder (the gateway client reads it
     *     from there).
     *  2. Rate limiter — keys its bucket on the now-populated PAT;
     *     rate-limited calls return an error before observation runs.
     *  3. Observation — timer + counter, tagged with status.
     *  4. Original tool.
     */
    private static McpStatelessServerFeatures.SyncToolSpecification wrap(
            McpStatelessServerFeatures.SyncToolSpecification original,
            String profile,
            ObservedToolDecorator observed,
            RateLimitedToolDecorator rateLimited,
            PatPropagatingToolDecorator patPropagator) {
        return patPropagator.decorate(
                rateLimited.decorate(
                        observed.decorate(original, profile),
                        profile));
    }

    // Note: SDK transports are NOT registered as standalone servlets via
    // ServletRegistrationBean. KeltaMcpController dispatches to them via
    // @RequestMapping("/{tenantSlug}/mcp/{profile:user|admin}") so the slug
    // stays in the URL end-to-end. The transports are still Spring beans
    // (above) so McpServer can attach them to its tool/resource registries
    // — they're just not bound to a servlet container path.

    private McpStatelessServerFeatures.SyncToolSpecification pingTool(String profile) {
        Tool tool = Tool.builder()
                .name("ping")
                .title("Ping (" + profile + ")")
                .description("Returns 'pong'. Smoke test that the " + profile + " MCP endpoint is reachable.")
                .inputSchema(Schemas.empty())
                .annotations(ToolHints.read())
                .build();

        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> CallToolResult.builder()
                        .content(List.of(new TextContent("pong (" + profile + ")")))
                        .build())
                .build();
    }
}
