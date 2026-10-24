package io.kelta.mcp.observe;

import io.kelta.mcp.error.McpErrorMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Innermost decorator: checks every supplied argument against the top-level
 * property {@code type} declared in the tool's {@code inputSchema} and rejects
 * a mismatch with a 400 {@link McpErrorMapper#badRequest} result before the
 * tool handler (and so the gateway) is reached. Tools therefore never see a
 * number where they declared a string and must not coerce one.
 *
 * <p>Only top-level types are checked — nested shapes stay the tool's
 * business. A {@code null} value is treated as an omitted argument, an
 * {@code integer} accepts any integral number, and a property with no
 * declared type (or an undeclared argument) is left alone.
 *
 * <p>Sits inside {@link ObservedToolDecorator}, so rejected calls are still
 * counted as {@code status=error} tool calls.
 */
@Component
public class ArgumentTypeValidatingToolDecorator {

    static final String CODE = "INVALID_ARGUMENT_TYPE";

    public SyncToolSpecification decorate(SyncToolSpecification original) {
        Map<String, Object> properties = declaredProperties(original.tool().inputSchema());
        var inner = original.callHandler();

        return SyncToolSpecification.builder()
                .tool(original.tool())
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null || args.isEmpty() || properties.isEmpty()) {
                        return inner.apply(context, request);
                    }
                    List<String> messages = new ArrayList<>();
                    List<Map<String, Object>> errors = new ArrayList<>();
                    for (Map.Entry<String, Object> arg : args.entrySet()) {
                        Object value = arg.getValue();
                        if (value == null) continue;
                        List<String> expected = declaredTypes(properties.get(arg.getKey()));
                        if (expected.isEmpty() || expected.stream().anyMatch(t -> matches(t, value))) {
                            continue;
                        }
                        String detail = "Argument \"" + arg.getKey() + "\" must be "
                                + String.join(" or ", expected) + ", got " + jsonType(value) + ".";
                        messages.add(detail);
                        errors.add(error(arg.getKey(), detail));
                    }
                    if (errors.isEmpty()) {
                        return inner.apply(context, request);
                    }
                    return McpErrorMapper.badRequest(
                            "Invalid arguments for tool \"" + original.tool().name() + "\": "
                                    + String.join(" ", messages),
                            errors);
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> declaredProperties(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) {
            return Map.of();
        }
        return (Map<String, Object>) properties;
    }

    private static List<String> declaredTypes(Object property) {
        if (!(property instanceof Map<?, ?> descriptor)) {
            return List.of();
        }
        Object type = descriptor.get("type");
        if (type instanceof String s) {
            return List.of(s);
        }
        if (type instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> isIntegral(value);
            case "array" -> value instanceof List<?> || value.getClass().isArray();
            case "object" -> value instanceof Map<?, ?>;
            case "null" -> false;
            default -> true;
        };
    }

    private static boolean isIntegral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof BigDecimal d) {
            return d.stripTrailingZeros().scale() <= 0;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) && d == Math.rint(d);
        }
        return false;
    }

    private static String jsonType(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        if (value instanceof List<?> || value.getClass().isArray()) return "array";
        if (value instanceof Map<?, ?>) return "object";
        return value.getClass().getSimpleName();
    }

    private static Map<String, Object> error(String argument, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "400");
        error.put("code", CODE);
        error.put("title", "Invalid argument type");
        error.put("detail", detail);
        error.put("source", Map.of("parameter", argument));
        return error;
    }
}
