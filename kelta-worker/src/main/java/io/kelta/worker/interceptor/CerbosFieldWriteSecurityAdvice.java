package io.kelta.worker.interceptor;

import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Map;
import java.util.Set;

/**
 * Strips HIDDEN, READ_ONLY and MASKED fields from record write bodies based on Cerbos field-level
 * write permissions: PUT/PATCH requests, and the POST of a record create — the generic collection
 * router's top-level and child-resource create handlers.
 *
 * <p>Create and update get the same rule: a field the caller may not write is removed from the
 * body, whether it arrives in {@code attributes} or as a JSON:API {@code relationships} entry (the
 * router stores a to-one relationship as the field's value). On a create the stored record then
 * gets the field's default, or null; on an update the stored value stays. The rule itself lives in
 * {@link FieldWriteGuard}, shared with the atomic operations endpoint.
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

    /** The generic router's create handlers, whose POST bodies are record writes. */
    private static final Set<String> CREATE_HANDLERS = Set.of("create", "createChild");

    private final FieldWriteGuard fieldWriteGuard;
    private final CerbosPermissionResolver permissionResolver;
    private final boolean permissionsEnabled;

    public CerbosFieldWriteSecurityAdvice(
            FieldWriteGuard fieldWriteGuard,
            CerbosPermissionResolver permissionResolver,
            @Value("${kelta.gateway.security.permissions-enabled:true}") boolean permissionsEnabled) {
        this.fieldWriteGuard = fieldWriteGuard;
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

        // PUT/PATCH on collection data endpoints, and POST to the router's record-create handlers
        boolean update = "PUT".equals(method) || "PATCH".equals(method);
        boolean create = "POST".equals(method) && isRecordCreate(methodParameter);
        if (!update && !create) return false;
        if (!path.startsWith("/api/")) return false;
        if (path.startsWith("/api/admin/") || path.startsWith("/api/me/")) return false;
        if (ApiPaths.isMetadataPath(path)) return false;
        if (!permissionResolver.hasIdentity(request)) return false;

        return true;
    }

    /**
     * Whether the body belongs to one of {@link DynamicCollectionRouter}'s create handlers.
     * Matched on the handler method, not the path: other controllers accept POST bodies under
     * the same paths that are not record writes.
     */
    private static boolean isRecordCreate(MethodParameter methodParameter) {
        return methodParameter != null
                && methodParameter.getMethod() != null
                && DynamicCollectionRouter.class.isAssignableFrom(methodParameter.getContainingClass())
                && CREATE_HANDLERS.contains(methodParameter.getMethod().getName());
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

        // Navigate JSON:API structure: { "data": { "attributes": { ... }, "relationships": { ... } } }
        Map<String, Object> typedBody = (Map<String, Object>) bodyMap;
        Object dataObj = typedBody.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            // Not JSON:API format — try flat attributes
            fieldWriteGuard.stripUnwritable(typedBody, null, email, profileId, tenantId, collectionId);
            return body;
        }

        Map<String, Object> data = (Map<String, Object>) dataMap;
        Map<String, Object> attributes = data.get("attributes") instanceof Map<?, ?> attrMap
                ? (Map<String, Object>) attrMap : null;
        Map<String, Object> relationships = data.get("relationships") instanceof Map<?, ?> relMap
                ? (Map<String, Object>) relMap : null;
        if (attributes == null && relationships == null) return body;

        fieldWriteGuard.stripUnwritable(attributes, relationships, email, profileId, tenantId, collectionId);

        return body;
    }
}
