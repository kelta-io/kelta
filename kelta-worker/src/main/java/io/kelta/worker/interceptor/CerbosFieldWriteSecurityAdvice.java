package io.kelta.worker.interceptor;

import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;
import java.util.*;

/**
 * Strips HIDDEN and READ_ONLY fields from PUT/PATCH request bodies
 * based on Cerbos field-level write permissions.
 *
 * <p>Complements {@link CerbosFieldSecurityAdvice} which handles read-side stripping.
 * Together they provide bidirectional field-level security enforcement.
 *
 * <p>Fail-closed: if Cerbos is unreachable, all non-system fields are stripped.
 *
 * @since 1.0.0
 */
@ControllerAdvice
@Order(10)
public class CerbosFieldWriteSecurityAdvice extends RequestBodyAdviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(CerbosFieldWriteSecurityAdvice.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "createdAt", "updatedAt", "createdBy", "updatedBy"
    );

    private final CerbosAuthorizationService authzService;
    private final CerbosPermissionResolver permissionResolver;
    private final boolean permissionsEnabled;

    public CerbosFieldWriteSecurityAdvice(
            CerbosAuthorizationService authzService,
            CerbosPermissionResolver permissionResolver,
            @Value("${kelta.gateway.security.permissions-enabled:true}") boolean permissionsEnabled) {
        this.authzService = authzService;
        this.permissionResolver = permissionResolver;
        this.permissionsEnabled = permissionsEnabled;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        if (!permissionsEnabled) return false;

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return false;

        HttpServletRequest request = attrs.getRequest();
        String method = request.getMethod();
        String path = request.getRequestURI();

        // Only apply to PUT/PATCH on collection data endpoints
        if (!"PUT".equals(method) && !"PATCH".equals(method)) return false;
        if (!path.startsWith("/api/")) return false;
        if (path.startsWith("/api/admin/") || path.startsWith("/api/me/")) return false;
        if (isMetadataPath(path)) return false;
        if (!permissionResolver.hasIdentity(request)) return false;

        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage,
                                 MethodParameter parameter, Type targetType,
                                 Class<? extends HttpMessageConverter<?>> converterType) {
        if (!(body instanceof Map<?, ?> bodyMap)) return body;

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return body;

        HttpServletRequest request = attrs.getRequest();
        String email = permissionResolver.getEmail(request);
        String profileId = permissionResolver.getProfileId(request);
        String tenantId = permissionResolver.getTenantId(request);
        // The collection being written — the child on a child-resource route.
        String collectionId = ApiPaths.collectionName(request);

        // Navigate JSON:API structure: { "data": { "attributes": { ... } } }
        Map<String, Object> typedBody = (Map<String, Object>) bodyMap;
        Object dataObj = typedBody.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            // Not JSON:API format — try flat attributes
            stripFromAttributes(typedBody, email, profileId, tenantId, collectionId);
            return body;
        }

        Map<String, Object> data = (Map<String, Object>) dataMap;
        Object attrObj = data.get("attributes");
        if (!(attrObj instanceof Map<?, ?> attrMap)) return body;

        Map<String, Object> attributes = (Map<String, Object>) attrMap;
        stripFromAttributes(attributes, email, profileId, tenantId, collectionId);

        return body;
    }

    private void stripFromAttributes(Map<String, Object> attributes, String email,
                                      String profileId, String tenantId, String collectionId) {
        // Collect non-system fields to check
        List<String> fieldsToCheck = attributes.keySet().stream()
                .filter(f -> !SYSTEM_FIELDS.contains(f))
                .toList();

        if (fieldsToCheck.isEmpty()) return;

        // Batch Cerbos check for write permission
        List<String> allowedFields;
        try {
            allowedFields = authzService.batchCheckFieldAccess(
                    email, profileId, tenantId, collectionId, fieldsToCheck, "write");
        } catch (Exception e) {
            // Fail-closed: deny all non-system field writes if Cerbos unreachable
            log.error("Cerbos unreachable for write field check — fail-closed, stripping all non-system fields", e);
            securityLog.warn("security_event=FIELD_WRITE_DENIED_CERBOS_FAILURE user={} collection={} fields={}",
                    email, collectionId, fieldsToCheck);
            for (String field : fieldsToCheck) {
                attributes.remove(field);
            }
            return;
        }

        Set<String> allowedSet = new HashSet<>(allowedFields);
        List<String> strippedFields = fieldsToCheck.stream()
                .filter(f -> !allowedSet.contains(f))
                .toList();

        if (!strippedFields.isEmpty()) {
            log.debug("Stripping {} write-denied fields from {} for user={}",
                    strippedFields.size(), collectionId, email);
            securityLog.info("security_event=FIELD_WRITE_DENIED user={} collection={} fields={}",
                    email, collectionId, strippedFields);
            for (String field : strippedFields) {
                attributes.remove(field);
            }
        }
    }

    private boolean isMetadataPath(String path) {
        return path.startsWith("/api/collections")
                || path.startsWith("/api/profiles")
                || path.startsWith("/api/security-audit-logs")
                || path.startsWith("/api/plugins")
                || path.startsWith("/api/oidc")
                || path.startsWith("/api/tenants")
                || path.startsWith("/api/metrics")
                || path.startsWith("/api/flows");
    }
}
