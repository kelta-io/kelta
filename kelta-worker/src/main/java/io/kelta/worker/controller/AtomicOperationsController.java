package io.kelta.worker.controller;

import tools.jackson.databind.ObjectMapper;
import io.kelta.jsonapi.AtomicOperation;
import io.kelta.jsonapi.AtomicResult;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.AtomicOperationExecutor;
import io.kelta.runtime.router.AtomicOperationExecutor.AtomicOperationException;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.worker.interceptor.FieldWriteGuard;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CollectionLifecycleManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON:API Atomic Operations endpoint.
 *
 * <p>Accepts a batch of create/update/delete operations and executes them
 * within a single database transaction. All operations succeed or all fail.
 *
 * @see <a href="https://jsonapi.org/ext/atomic">JSON:API Atomic Operations</a>
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/operations")
public class AtomicOperationsController {

    private static final Logger log = LoggerFactory.getLogger(AtomicOperationsController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final int HARD_CEILING = 500;

    /**
     * System collections a batch may write. The executor calls the query engine directly, so a
     * system collection whose lifecycle lives in its own controller (DDL, registry refresh, caches)
     * would be written without it. These identity collections have no such side effects and are
     * written through this endpoint on purpose, behind {@code IdentityCollectionGuardHook}.
     */
    static final Set<String> SUPPORTED_SYSTEM_COLLECTIONS = Set.of(
            "users", "user-permission-sets", "group-memberships", "delegated-admin-scopes");

    private final AtomicOperationExecutor executor;
    private final CollectionRegistry collectionRegistry;
    private final CollectionLifecycleManager lifecycleManager;
    private final CerbosAuthorizationService authzService;
    private final CerbosPermissionResolver permissionResolver;
    private final FieldWriteGuard fieldWriteGuard;
    private final int maxOperations;

    public AtomicOperationsController(
            QueryEngine queryEngine,
            CollectionRegistry collectionRegistry,
            CollectionLifecycleManager lifecycleManager,
            CerbosAuthorizationService authzService,
            CerbosPermissionResolver permissionResolver,
            FieldWriteGuard fieldWriteGuard,
            @Value("${kelta.api.max-batch-operations:100}") int maxOperations) {
        this.executor = new AtomicOperationExecutor(queryEngine, collectionRegistry);
        this.collectionRegistry = collectionRegistry;
        this.lifecycleManager = lifecycleManager;
        this.authzService = authzService;
        this.permissionResolver = permissionResolver;
        this.fieldWriteGuard = fieldWriteGuard;
        this.maxOperations = Math.min(maxOperations, HARD_CEILING);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> executeOperations(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        String tenantId = TenantContext.get();

        // Parse operations from request body
        Object opsObj = body.get("atomic:operations");
        if (opsObj == null) {
            return ResponseEntity.badRequest().body(errorResponse(
                    "400", "INVALID_PAYLOAD", "Invalid request",
                    "Missing 'atomic:operations' array", null));
        }

        List<AtomicOperation> operations;
        try {
            operations = parseOperations(opsObj);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(
                    "400", "PARSE_ERROR", "Parse error",
                    e.getMessage() != null ? e.getMessage() : "Could not parse atomic:operations", null));
        }

        // Validate operation count
        if (operations.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse(
                    "400", "EMPTY_BATCH", "Empty batch", "No operations provided", null));
        }

        if (operations.size() > maxOperations) {
            return ResponseEntity.unprocessableEntity().body(errorResponse(
                    "422", "TOO_MANY_OPERATIONS", "Too many operations",
                    "Request contains " + operations.size() + " operations, maximum is " + maxOperations,
                    null));
        }

        ResponseEntity<?> unsupported = rejectUnsupportedCollections(operations);
        if (unsupported != null) {
            return unsupported;
        }

        // Per-operation object-level authorization. The gateway applies its
        // per-collection Cerbos verb check to dynamic routes but NOT to this
        // static /api/operations route, so we mirror it here — otherwise an
        // API_ACCESS caller could batch-write collections the normal route
        // would deny. Whole batch is denied if any operation is unauthorized
        // (atomic semantics), before anything executes.
        ResponseEntity<?> denial = authorizeOperations(operations, tenantId, request);
        if (denial != null) {
            return denial;
        }

        // Field-level write rules, as on a single-record create/update: a field the caller may
        // not write is dropped from that operation's attributes.
        operations = applyFieldWriteRules(operations, request);

        // Execute within transaction
        try {
            // CallerContextFilter has already resolved X-User-Id to the platform_user UUID.
            String actingUserId = CallerContext.current().map(CallerContext::userId).orElse(null);
            List<AtomicResult> results = executor.execute(operations, actingUserId);

            log.info("Atomic operations completed: {} operations for tenant {}",
                    operations.size(), tenantId);
            securityLog.info("security_event=ATOMIC_OPS_SUCCESS tenant={} count={}",
                    tenantId, operations.size());

            return ResponseEntity.ok(Map.of("atomic:results", results));
        } catch (AtomicOperationException e) {
            log.warn("Atomic operation {} failed at index {}: {}",
                    e.getOperationType(), e.getOperationIndex(), e.getMessage());
            rollbackBatch();

            if (e.getCause() instanceof ValidationException validation) {
                return ResponseEntity.badRequest().body(validationErrorResponse(validation, e.getOperationIndex()));
            }
            return ResponseEntity.unprocessableEntity().body(errorResponse(
                    "422", "OPERATION_FAILED", "Operation failed",
                    e.getMessage() != null ? e.getMessage() : "Atomic operation failed",
                    "/atomic:operations/" + e.getOperationIndex()));
        } catch (Exception e) {
            log.error("Unexpected error during atomic operations", e);
            rollbackBatch();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(
                    "500", "INTERNAL_ERROR", "Internal error",
                    "An unexpected error occurred", null));
        }
    }

    /**
     * Returns a 400 pointing at the first operation that targets a system collection outside
     * {@link #SUPPORTED_SYSTEM_COLLECTIONS}, or {@code null}. Runs before any operation executes,
     * so a rejected batch writes nothing. Unknown collections are left to the executor.
     */
    private ResponseEntity<?> rejectUnsupportedCollections(List<AtomicOperation> operations) {
        for (int i = 0; i < operations.size(); i++) {
            String collectionName = collectionOf(operations.get(i));
            if (collectionName == null || SUPPORTED_SYSTEM_COLLECTIONS.contains(collectionName)) {
                continue;
            }
            CollectionDefinition definition = collectionRegistry.get(collectionName);
            if (definition != null && definition.systemCollection()) {
                return ResponseEntity.badRequest().body(errorResponse(
                        "400", "UNSUPPORTED_COLLECTION", "Unsupported collection",
                        "Atomic operations do not support the system collection '" + collectionName
                                + "'; use POST /api/" + collectionName + " instead",
                        "/atomic:operations/" + i));
            }
        }
        return null;
    }

    /**
     * Enforces the gateway's per-collection Cerbos verb check on each operation.
     * Returns a 403 {@link ResponseEntity} if the caller lacks identity or any
     * operation targets a collection+action Cerbos denies; {@code null} if the
     * whole batch is authorized.
     *
     * <p>Denials are deduplicated per (collection, action) so a large batch on
     * one collection costs a single Cerbos check. Unknown collections are left
     * to the executor (which reports them cleanly). Fail-closed: a missing
     * identity or a Cerbos error denies the batch.
     */
    private ResponseEntity<?> authorizeOperations(List<AtomicOperation> operations,
                                                  String tenantId, HttpServletRequest request) {
        String email = permissionResolver.getEmail(request);
        String profileId = permissionResolver.getProfileId(request);
        String scopeTenant = permissionResolver.getTenantId(request);

        // This HTTP endpoint is only reached by authenticated callers (the gateway
        // sets identity headers after API_ACCESS). No identity ⇒ fail-closed.
        if (profileId == null || profileId.isBlank() || scopeTenant == null || scopeTenant.isBlank()) {
            securityLog.warn("security_event=ATOMIC_OPS_DENIED tenant={} reason=no_identity", tenantId);
            return forbidden("Authentication required for atomic operations");
        }

        // (collectionUuid, action) → allowed, checked once per distinct pair.
        Map<String, Boolean> decisions = new HashMap<>();
        for (AtomicOperation op : operations) {
            String action = actionFor(op.op());
            String collectionName = collectionOf(op);
            if (action == null || collectionName == null || collectionName.isBlank()) {
                continue; // executor validates op shape / unknown collections
            }
            String collectionUuid = lifecycleManager.getCollectionIdByName(collectionName);
            if (collectionUuid == null) {
                continue; // unknown collection — executor returns a clean error
            }
            String key = collectionUuid + ":" + action;
            boolean allowed = decisions.computeIfAbsent(key, k ->
                    authzService.checkCollectionAccess(email, profileId, scopeTenant, collectionUuid, action));
            if (!allowed) {
                securityLog.warn("security_event=ATOMIC_OPS_DENIED tenant={} profile={} collection={} action={}",
                        tenantId, profileId, collectionName, action);
                return forbidden("Insufficient permissions for " + action + " on " + collectionName);
            }
        }
        return null;
    }

    /**
     * Removes from each add/update operation the attributes the caller may not write, with the
     * rule {@code CerbosFieldWriteSecurityAdvice} applies to a single-record body (the advice does
     * not see the individual operations of a batch). Metadata collections are exempt, as they are
     * there. Returns new operations; the parsed ones are not modified.
     */
    private List<AtomicOperation> applyFieldWriteRules(List<AtomicOperation> operations,
                                                       HttpServletRequest request) {
        String email = permissionResolver.getEmail(request);
        String profileId = permissionResolver.getProfileId(request);
        String scopeTenant = permissionResolver.getTenantId(request);

        List<AtomicOperation> result = new ArrayList<>(operations.size());
        for (AtomicOperation op : operations) {
            String collectionName = collectionOf(op);
            if (op == null || actionFor(op.op()) == null || "remove".equals(op.op())
                    || op.data() == null || op.data().attributes() == null
                    || collectionName == null || FieldWriteGuard.isMetadataCollection(collectionName)) {
                result.add(op);
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>(op.data().attributes());
            fieldWriteGuard.stripUnwritable(attributes, null, email, profileId, scopeTenant, collectionName);
            AtomicOperation.ResourceData data = op.data();
            result.add(new AtomicOperation(op.op(), op.ref(), new AtomicOperation.ResourceData(
                    data.type(), data.id(), data.lid(), attributes, data.relationships())));
        }
        return result;
    }

    /** Collection name an operation targets: {@code data.type} for add, else {@code ref.type}. */
    private static String collectionOf(AtomicOperation op) {
        if (op == null || op.op() == null) {
            return null;
        }
        if ("add".equals(op.op())) {
            return op.data() != null ? op.data().type() : null;
        }
        if (op.ref() != null && op.ref().type() != null) {
            return op.ref().type();
        }
        return op.data() != null ? op.data().type() : null;
    }

    /** Maps an atomic op verb to the Cerbos action the gateway uses for the equivalent HTTP method. */
    private static String actionFor(String opVerb) {
        if (opVerb == null) {
            return null;
        }
        return switch (opVerb) {
            case "add" -> "create";
            case "update" -> "edit";
            case "remove" -> "delete";
            default -> null;
        };
    }

    private ResponseEntity<?> forbidden(String detail) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse(
                "403", "FORBIDDEN", "Forbidden", detail, null));
    }

    @SuppressWarnings("unchecked")
    private List<AtomicOperation> parseOperations(Object opsObj) {
        if (!(opsObj instanceof List<?> opsList)) {
            throw new IllegalArgumentException("'atomic:operations' must be an array");
        }

        ObjectMapper mapper = new ObjectMapper();
        return opsList.stream()
                .map(op -> mapper.convertValue(op, AtomicOperation.class))
                .toList();
    }

    /**
     * Marks the batch transaction rollback-only. The failure is answered with an error envelope
     * rather than rethrown, so without this the operations before the failing one would commit.
     */
    private static void rollbackBatch() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    /**
     * A field validation failure inside an operation is a 400, as it is on a single-record
     * write, with one error per field pointing into the failing operation.
     */
    private Map<String, Object> validationErrorResponse(ValidationException validation, int operationIndex) {
        String base = "/atomic:operations/" + operationIndex;
        if (validation.getValidationResult() == null || validation.getValidationResult().errors().isEmpty()) {
            return errorResponse("400", "VALIDATION_FAILED", "Validation Error", validation.getMessage(), base);
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError fieldError : validation.getValidationResult().errors()) {
            var error = new LinkedHashMap<String, Object>();
            error.put("status", "400");
            error.put("code", fieldError.constraint().isBlank() ? "VALIDATION_FAILED" : fieldError.constraint());
            error.put("title", "Validation Error");
            error.put("detail", fieldError.message());
            error.put("source", Map.of("pointer", base + "/data/attributes/" + fieldError.fieldName()));
            errors.add(error);
        }
        return Map.of("errors", errors);
    }

    private Map<String, Object> errorResponse(String status, String code, String title, String detail, String pointer) {
        var error = new java.util.LinkedHashMap<String, Object>();
        error.put("status", status);
        error.put("code", code);
        error.put("title", title);
        error.put("detail", detail);
        if (pointer != null) {
            error.put("source", Map.of("pointer", pointer));
        }
        return Map.of("errors", List.of(error));
    }
}
