package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates a record in the {@code validation-rules} system collection.
 *
 * <p>The worker's semantics are an <em>error condition</em>: the record is
 * REJECTED when {@code errorConditionFormula} evaluates to true. (This tool
 * previously documented the opposite — "expression must evaluate true" — and
 * posted to a non-existent {@code /api/validationRules} path; both are fixed.
 * A legacy {@code expression} argument is still accepted and is negated into
 * {@code NOT(...)} to preserve its documented must-hold meaning.)
 */
@Component
public class CreateValidationRuleTool implements AdminTool {

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public CreateValidationRuleTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collectionName", Schemas.string("Collection the rule applies to."));
        properties.put("name", Schemas.string("Rule name (unique within collection)."));
        properties.put("errorConditionFormula", Schemas.string(
                "Formula describing the ERROR condition: the record is REJECTED when this evaluates true. "
                + "Reference fields by attribute name; functions like AND(), OR(), NOT(), ISBLANK() are available. "
                + "Example: AND(NOT(ISBLANK(amountMin)), NOT(ISBLANK(amountMax)), amountMax < amountMin)."));
        properties.put("expression", Schemas.string(
                "(Deprecated) Boolean expression that must hold for the record to be valid. "
                + "Wrapped as NOT(expression) into errorConditionFormula. Prefer errorConditionFormula."));
        properties.put("errorMessage", Schemas.string(
                "Error message shown to the user when the rule rejects the record."));
        properties.put("errorField", Schemas.string(
                "Optional field name the error is attributed to in API responses and forms."));
        properties.put("evaluateOn", Schemas.string(
                "When the rule runs: CREATE, UPDATE, or CREATE_AND_UPDATE (default)."));
        properties.put("severity", Schemas.string("ERROR (default, blocks the save) or WARNING."));
        properties.put("active", Schemas.bool("Whether the rule is active (default true).", true));

        Tool tool = Tool.builder()
                .name("create_validation_rule")
                .title("Create Validation Rule")
                .description("Create a validation rule on a collection. The record is rejected at create/update time when errorConditionFormula evaluates TRUE. To test related records, reference a ROLLUP_SUMMARY field of the collection in the formula: it is computed from the child rows at write time (a client-supplied value is ignored), e.g. AND(state = \"closed\", openTasks > 0) with openTasks a filtered COUNT rollup. Wraps POST /api/validation-rules.")
                .inputSchema(Schemas.object(properties, List.of("collectionName", "name", "errorMessage")))
                .annotations(ToolHints.write(false, false))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object cn = args.get("collectionName");
                    Object name = args.get("name");
                    Object msg = args.get("errorMessage");
                    if (cn == null || cn.toString().isBlank()
                            || name == null || name.toString().isBlank()
                            || msg == null || msg.toString().isBlank()) {
                        return error("Arguments \"collectionName\", \"name\", and \"errorMessage\" are required.");
                    }

                    String formula = null;
                    if (args.get("errorConditionFormula") instanceof String f && !f.isBlank()) {
                        formula = f;
                    } else if (args.get("expression") instanceof String legacy && !legacy.isBlank()) {
                        // Legacy semantics: expression must hold → error when it does not.
                        formula = "NOT(" + legacy + ")";
                    }
                    if (formula == null) {
                        return error("Provide \"errorConditionFormula\" (or the deprecated \"expression\").");
                    }

                    String collectionId = lookups.collectionIdByName(cn.toString());
                    if (collectionId == null) {
                        return error("Collection \"" + cn + "\" not found.");
                    }

                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("collectionId", collectionId);
                    attrs.put("name", name.toString());
                    attrs.put("errorConditionFormula", formula);
                    attrs.put("errorMessage", msg.toString());
                    attrs.put("active", args.get("active") instanceof Boolean b ? b : Boolean.TRUE);
                    attrs.put("evaluateOn", args.get("evaluateOn") instanceof String ev && !ev.isBlank()
                            ? ev : "CREATE_AND_UPDATE");
                    attrs.put("severity", args.get("severity") instanceof String sv && !sv.isBlank()
                            ? sv : "ERROR");
                    if (args.get("errorField") instanceof String ef && !ef.isBlank()) {
                        attrs.put("errorField", ef);
                    }

                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "validation-rules",
                            "attributes", attrs));
                    try {
                        return McpErrorMapper.toResult(gateway.post("/api/validation-rules", body));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
