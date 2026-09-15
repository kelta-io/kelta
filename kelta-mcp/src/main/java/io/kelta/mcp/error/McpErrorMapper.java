package io.kelta.mcp.error;

import io.kelta.mcp.client.GatewayHttpClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates gateway HTTP responses into MCP {@link CallToolResult}s.
 *
 * <p>Normal 2xx responses become regular tool results carrying the body
 * verbatim as text. 4xx/5xx responses become {@code isError: true} tool
 * results with a short, human-readable message, plus a {@code
 * structuredContent} of {@code {status, errors:[...]}} — the gateway's
 * JSON:API {@code errors[]} array (see conventions.md), parsed verbatim so a
 * calling agent can branch on {@code errors[0].code} / {@code
 * errors[0].source.pointer} instead of parsing the prose message. Callers
 * that don't read {@code structuredContent} see no change.
 *
 * <p>Token redaction: the gateway should never echo a PAT back, but we
 * defensively scrub any {@code klt_…} substring before emitting MCP
 * content. This is a belt-and-suspenders measure — if a regression in
 * the gateway ever exposes the token in an error message, it will not
 * leak through us. Redaction happens on the raw body text before it is
 * parsed into {@code structuredContent}, so both forms are scrubbed.
 */
public final class McpErrorMapper {

    private static final Pattern PAT_PATTERN = Pattern.compile("klt_[A-Za-z0-9]+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpErrorMapper() {}

    public static CallToolResult toResult(GatewayHttpClient.Response response) {
        String body = redact(response.body());
        if (response.isSuccess()) {
            return CallToolResult.builder()
                    .content(List.of(new TextContent(body == null ? "" : body)))
                    .build();
        }
        int statusCode = response.status() == null ? 0 : response.status().value();
        String message = "Gateway returned HTTP " + statusCode
                + (body == null || body.isBlank() ? "" : " — " + body);
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .structuredContent(structuredError(statusCode, body))
                .build();
    }

    /**
     * {@code {status, errors:[...]}} where {@code errors} is the gateway's own
     * JSON:API error array, parsed and re-emitted verbatim (not reshaped) — a
     * body without a parseable {@code errors} array (e.g. plain text) yields
     * an empty array rather than failing the mapping.
     */
    private static Map<String, Object> structuredError(int statusCode, String redactedBody) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", statusCode);
        structured.put("errors", parseErrors(redactedBody));
        return structured;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parseErrors(String redactedBody) {
        if (redactedBody == null || redactedBody.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(redactedBody);
            JsonNode errors = root.path("errors");
            if (errors.isArray()) {
                return MAPPER.convertValue(errors, List.class);
            }
        } catch (RuntimeException e) {
            // Not a parseable JSON:API error envelope — fall through to empty.
        }
        return List.of();
    }

    public static CallToolResult fromException(Throwable t) {
        String message = redact(t.getClass().getSimpleName() + ": " + t.getMessage());
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent("Tool call failed — " + message)))
                .build();
    }

    static String redact(String input) {
        if (input == null) return null;
        return PAT_PATTERN.matcher(input).replaceAll("klt_***REDACTED***");
    }
}
