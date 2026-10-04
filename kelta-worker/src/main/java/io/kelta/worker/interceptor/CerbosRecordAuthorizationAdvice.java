package io.kelta.worker.interceptor;

import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.RecordRuleIndex;
import io.kelta.worker.service.RecordShareAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Filters records from API responses based on Cerbos record-level authorization.
 *
 * <p>For each record in a JSON:API response, checks Cerbos with all record
 * attributes. Records denied by Cerbos are removed from the response — unless a
 * manual record share ({@link RecordShareAccessService}) widens the user's
 * access to that record (READ share → read; EDIT share → read + edit).
 *
 * <p>Only applies to {@code /api/} paths when permissions are enabled.
 */
@ControllerAdvice
public class CerbosRecordAuthorizationAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(CerbosRecordAuthorizationAdvice.class);

    private final CerbosAuthorizationService authzService;
    private final CerbosPermissionResolver permissionResolver;
    private final RecordShareAccessService recordShareAccessService;
    private final RecordRuleIndex recordRuleIndex;
    private final boolean permissionsEnabled;

    public CerbosRecordAuthorizationAdvice(
            CerbosAuthorizationService authzService,
            CerbosPermissionResolver permissionResolver,
            RecordShareAccessService recordShareAccessService,
            RecordRuleIndex recordRuleIndex,
            @Value("${kelta.gateway.security.permissions-enabled:true}") boolean permissionsEnabled) {
        this.authzService = authzService;
        this.permissionResolver = permissionResolver;
        this.recordShareAccessService = recordShareAccessService;
        this.recordRuleIndex = recordRuleIndex;
        this.permissionsEnabled = permissionsEnabled;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return permissionsEnabled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                   Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                   ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return body;
        }

        // A controller that scopes its own data to the caller is exempt: portal
        // members hold no record grants, so the filter below would empty their own
        // watches/wins/devices/billing while every write still succeeded. Checked on
        // the handler, not the path, so the generic collection route on those same
        // paths keeps full record-level filtering.
        if (returnType != null
                && SelfScopedController.class.isAssignableFrom(returnType.getContainingClass())) {
            return body;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        String path = httpRequest.getRequestURI();

        // Only apply to collection API paths (user record data, not metadata).
        // /api/telehealth/** is excluded: those endpoints enforce access in the
        // controller (portal users are scoped to their own appointments via
        // view=mine / participant shares, staff via provider ownership), and
        // their responses are plain maps — not generic record envelopes. Running
        // the record-level Cerbos check here emptied every portal user's
        // appointment list, because portal profiles have no record grants
        // (found 2026-07-12 building the headless portal).
        if (!path.startsWith("/api/") || path.startsWith("/api/admin/") || path.startsWith("/api/me/")
                || path.startsWith("/api/telehealth/") || isMetadataPath(path)) {
            return body;
        }

        if (!permissionResolver.hasIdentity(httpRequest)) {
            return body;
        }

        if (!(body instanceof Map)) {
            return body;
        }

        Map<String, Object> responseBody = (Map<String, Object>) body;
        Object data = responseBody.get("data");
        if (data == null) {
            return body;
        }

        String email = permissionResolver.getEmail(httpRequest);
        String profileId = permissionResolver.getProfileId(httpRequest);
        String tenantId = permissionResolver.getTenantId(httpRequest);
        String collectionId = extractCollectionId(path);
        String action = mapMethodToAction(httpRequest.getMethod());

        if (data instanceof List<?> records) {
            // Collect all records for a single batched Cerbos call
            List<Map<String, Object>> typedRecords = new ArrayList<>();
            for (Object record : records) {
                if (record instanceof Map<?, ?> recordMap) {
                    typedRecords.add((Map<String, Object>) recordMap);
                }
            }

            Set<String> allowedIds;
            if (recordRuleIndex.hasRecordVariantRules(tenantId, collectionId)) {
                allowedIds = new HashSet<>(authzService.batchCheckRecordAccess(
                        email, profileId, tenantId, collectionId, typedRecords, action));
            } else {
                // No record-variant rules: the record policy decides identically for
                // every record, so one cached collection-wide check covers the page
                // and Cerbos never sees the record payloads.
                allowedIds = new HashSet<>();
                if (authzService.checkCollectionWideRecordAccess(
                        email, profileId, tenantId, collectionId, action)) {
                    for (Map<String, Object> record : typedRecords) {
                        String id = (String) record.get("id");
                        if (id != null) {
                            allowedIds.add(id);
                        }
                    }
                }
            }

            // Manual record shares may widen access to records the profile denied
            Set<String> deniedIds = typedRecords.stream()
                    .map(r -> (String) r.get("id"))
                    .filter(id -> id != null && !allowedIds.contains(id))
                    .collect(Collectors.toSet());
            if (!deniedIds.isEmpty()) {
                allowedIds.addAll(recordShareAccessService.widen(email, collectionId, deniedIds, action));
            }
            final Set<String> finalAllowed = allowedIds;

            List<Map<String, Object>> filtered = typedRecords.stream()
                    .filter(r -> {
                        String id = (String) r.get("id");
                        return id == null || finalAllowed.contains(id);
                    })
                    .toList();

            int removed = typedRecords.size() - filtered.size();
            if (removed > 0) {
                log.debug("Filtered {} records from response for user={} collection={}",
                        removed, email, collectionId);
            }
            // Copy into a mutable map — controllers may return Map.of(...) which is immutable.
            Map<String, Object> result = new LinkedHashMap<>(responseBody);
            result.put("data", filtered);
            if (removed > 0) {
                reducePaginationCounts(result, removed);
            }
            return result;
        } else if (data instanceof Map<?, ?> singleRecord) {
            Map<String, Object> typedRecord = (Map<String, Object>) singleRecord;
            if (!isRecordAllowed(email, profileId, tenantId, collectionId, typedRecord, action)) {
                log.debug("Denied single record for user={} collection={}", email, collectionId);
                Map<String, Object> result = new LinkedHashMap<>(responseBody);
                result.put("data", null);
                return result;
            }
        }

        return responseBody;
    }

    /**
     * Takes the dropped rows out of the list's pagination counts, so the response does not report
     * rows the caller may not read ({@code data: []} with {@code totalCount: 3} confirms they
     * exist). Same page-local arithmetic as {@code DynamicCollectionRouter.restrictSharedSystemRows}.
     * The router emits {@code meta} and the legacy {@code metadata} as one shared map; they stay
     * one shared (adjusted) map here.
     */
    private static void reducePaginationCounts(Map<String, Object> result, int removed) {
        Map<Object, Map<String, Object>> adjusted = new IdentityHashMap<>();
        for (String key : List.of("meta", "metadata")) {
            if (result.get(key) instanceof Map<?, ?> counts && counts.get("totalCount") instanceof Number) {
                result.put(key, adjusted.computeIfAbsent(counts, c -> withReducedCounts(counts, removed)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withReducedCounts(Map<?, ?> counts, int removed) {
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) counts);
        long newTotal = Math.max(0, ((Number) counts.get("totalCount")).longValue() - removed);
        copy.put("totalCount", newTotal);
        if (counts.get("totalPages") != null
                && counts.get("pageSize") instanceof Number pageSize && pageSize.intValue() > 0) {
            copy.put("totalPages", (int) Math.ceil((double) newTotal / pageSize.intValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private boolean isRecordAllowed(String email, String profileId, String tenantId,
                                     String collectionId, Map<String, Object> record, String action) {
        String recordId = (String) record.get("id");
        if (recordId == null) {
            return true;
        }

        if (!recordRuleIndex.hasRecordVariantRules(tenantId, collectionId)) {
            if (authzService.checkCollectionWideRecordAccess(
                    email, profileId, tenantId, collectionId, action)) {
                return true;
            }
            return !recordShareAccessService
                    .widen(email, collectionId, Set.of(recordId), action)
                    .isEmpty();
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        Object attrObj = record.get("attributes");
        if (attrObj instanceof Map<?, ?> attrMap) {
            attributes.putAll((Map<String, Object>) attrMap);
        }

        if (authzService.checkRecordAccess(email, profileId, tenantId,
                collectionId, recordId, attributes, action)) {
            return true;
        }
        // A manual record share may still widen access to this record
        return !recordShareAccessService
                .widen(email, collectionId, Set.of(recordId), action)
                .isEmpty();
    }

    /**
     * Checks if the path is a platform metadata endpoint that should not have
     * record-level authorization applied.
     */
    private boolean isMetadataPath(String path) {
        return path.startsWith("/api/collections")
                || path.startsWith("/api/profiles")
                || path.startsWith("/api/security-audit-logs")
                || path.startsWith("/api/plugins")
                || path.startsWith("/api/oidc")
                || path.startsWith("/api/tenants")
                || path.startsWith("/api/metrics")
                || path.startsWith("/api/flows")
                || path.startsWith("/api/api-specs")
                || path.startsWith("/api/api-operations")
                || path.startsWith("/api/credentials")
                || path.startsWith("/api/connected-apps")
                || path.startsWith("/api/devices");
    }

    private String extractCollectionId(String path) {
        // Path format: /api/{collectionName} or /api/{collectionName}/{id}
        String[] parts = path.split("/");
        if (parts.length >= 3) {
            return parts[2];
        }
        return "";
    }

    private String mapMethodToAction(String method) {
        if (method == null) return "read";
        return switch (method) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "edit";
            case "DELETE" -> "delete";
            default -> "read";
        };
    }
}
