package io.kelta.worker.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

/**
 * Shared request-path classification for the record/field-security advices and the
 * masked-field predicate guard. Keeping the reserved-path set and the matching
 * rule in one place stops the two components from drifting — a mismatch would
 * mean one enforces masking on a path the other skips.
 */
final class ApiPaths {

    private ApiPaths() {}

    /**
     * Platform metadata / controller endpoints that serve configuration, not user
     * records, and are therefore exempt from field-level security + masking.
     */
    private static final List<String> METADATA_PREFIXES = List.of(
            "/api/collections",
            "/api/profiles",
            "/api/security-audit-logs",
            "/api/plugins",
            "/api/oidc",
            "/api/tenants",
            "/api/metrics",
            "/api/flows");

    /**
     * True when {@code path} is a reserved metadata endpoint. Matches by exact
     * path segment ({@code equals} or a {@code "/"} boundary) — <em>not</em> a raw
     * prefix — so a tenant user collection whose name merely starts with a reserved
     * token (e.g. {@code flowsheet} vs {@code /api/flows}, {@code metrics2} vs
     * {@code /api/metrics}) is still subject to masking/FLS instead of silently
     * skipping it.
     */
    static boolean isMetadataPath(String path) {
        for (String prefix : METADATA_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Path variable {@code DynamicCollectionRouter} binds the child collection to on
     * {@code /api/{parentName}/{parentId}/{childName}[/{childId}]}.
     */
    static final String CHILD_NAME_VARIABLE = "childName";

    /**
     * The collection whose records a request reads or writes: the child collection when the
     * request was matched to one of the router's child-resource handlers (the handler serves
     * and writes the child's records, never the parent's), otherwise the first segment after
     * {@code /api/}. The child is taken from the matched handler's path variables rather than
     * from the path's depth, because a dedicated controller can claim a literal path of the same
     * depth ({@code /api/attachments/{id}/finalize}) whose records belong to the first segment.
     */
    static String collectionName(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars
                && vars.get(CHILD_NAME_VARIABLE) instanceof String childName && !childName.isBlank()) {
            return childName;
        }
        String[] parts = request.getRequestURI().split("/");
        return parts.length >= 3 ? parts[2] : "";
    }
}
