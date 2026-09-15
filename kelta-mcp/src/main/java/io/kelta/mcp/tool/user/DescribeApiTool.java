package io.kelta.mcp.tool.user;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.kelta.mcp.tool.UserTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

@Component
public class DescribeApiTool implements UserTool {

    private final GatewayHttpClient gateway;

    public DescribeApiTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Tool tool = Tool.builder()
                .name("describe_api")
                .title("Describe API")
                .description("Return the auto-generated OpenAPI 3.0 spec for the current tenant: JSON:API CRUD "
                        + "paths and schemas for every collection, including system collections. Specialized "
                        + "controllers (flows, approvals, bulk) are NOT in this spec; use the dedicated tools "
                        + "for those. For one collection's field list, types, enums and validation rules, use "
                        + "get_collection_schema instead of parsing this spec. Example: call with no arguments, "
                        + "then look up the \"/accounts\" path in the returned \"paths\" object for its exact "
                        + "request/response shape.")
                .inputSchema(Schemas.empty())
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    try {
                        return McpErrorMapper.toResult(gateway.get("/api/docs/openapi.json"));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }
}
