package io.kelta.runtime.router;

import io.kelta.jsonapi.AtomicOperation;
import io.kelta.jsonapi.AtomicResult;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Executes a list of JSON:API Atomic Operations sequentially.
 *
 * <p>MUST be called within a {@code @Transactional} context — the caller
 * is responsible for transaction management. On any failure, this executor
 * throws an exception so the caller's transaction rolls back.
 *
 * <p>Maintains a local ID (lid) map for resolving server-generated IDs
 * within the same batch request.
 *
 * @since 1.0.0
 */
public class AtomicOperationExecutor {

    private static final Logger log = LoggerFactory.getLogger(AtomicOperationExecutor.class);

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;

    public AtomicOperationExecutor(QueryEngine queryEngine, CollectionRegistry collectionRegistry) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
    }

    /**
     * Executes all operations sequentially, maintaining lid→id mappings.
     *
     * @param operations the list of atomic operations to execute
     * @return the list of results, one per operation
     * @throws AtomicOperationException if any operation fails (caller should rollback)
     */
    public List<AtomicResult> execute(List<AtomicOperation> operations) {
        return execute(operations, null);
    }

    /**
     * Executes all operations sequentially, stamping {@code createdBy}/{@code updatedBy}
     * with {@code actingUserId} the way {@link DynamicCollectionRouter} does for a single
     * write — overwriting any client-supplied value.
     *
     * @param operations   the list of atomic operations to execute
     * @param actingUserId the caller's {@code platform_user} UUID, or null to stamp nothing
     *                     (internal tier)
     * @return the list of results, one per operation
     * @throws AtomicOperationException if any operation fails (caller should rollback)
     */
    public List<AtomicResult> execute(List<AtomicOperation> operations, String actingUserId) {
        // Validate all operations first
        for (int i = 0; i < operations.size(); i++) {
            operations.get(i).validate(i);
        }

        Map<String, String> lidMap = new HashMap<>();
        List<AtomicResult> results = new ArrayList<>();

        for (int i = 0; i < operations.size(); i++) {
            AtomicOperation op = operations.get(i);
            try {
                AtomicResult result = executeOne(op, i, lidMap, actingUserId);
                results.add(result);
            } catch (AtomicOperationException e) {
                throw e; // Already has operation index
            } catch (Exception e) {
                throw new AtomicOperationException(i, op.op(), e.getMessage(), e);
            }
        }

        return results;
    }

    private AtomicResult executeOne(AtomicOperation op, int index, Map<String, String> lidMap,
                                    String actingUserId) {
        return switch (op.op()) {
            case "add" -> executeAdd(op, index, lidMap, actingUserId);
            case "update" -> executeUpdate(op, index, lidMap, actingUserId);
            case "remove" -> executeRemove(op, index, lidMap);
            default -> throw new AtomicOperationException(index, op.op(), "Unknown operation: " + op.op());
        };
    }

    private AtomicResult executeAdd(AtomicOperation op, int index, Map<String, String> lidMap,
                                    String actingUserId) {
        String type = op.data().type();
        CollectionDefinition definition = resolveCollection(type, index);

        Map<String, Object> attributes = op.data().attributes() != null
                ? new LinkedHashMap<>(op.data().attributes())
                : new LinkedHashMap<>();

        // Resolve any lid references in relationship attributes
        resolveLidsInAttributes(attributes, lidMap);

        if (actingUserId != null) {
            attributes.put("createdBy", actingUserId);
            attributes.put("updatedBy", actingUserId);
        }

        Map<String, Object> created = queryEngine.create(definition, attributes);
        String id = (String) created.get("id");

        // Track lid→id if lid was provided
        if (op.data().lid() != null) {
            lidMap.put(op.data().lid(), id);
            log.debug("Atomic add: lid={} → id={} for type={}", op.data().lid(), id, type);
        }

        return AtomicResult.of(type, id, op.data().lid(), stripId(created));
    }

    private AtomicResult executeUpdate(AtomicOperation op, int index, Map<String, String> lidMap,
                                       String actingUserId) {
        String type = op.ref().type();
        String id = resolveId(op.ref(), lidMap, index);
        CollectionDefinition definition = resolveCollection(type, index);

        Map<String, Object> attributes = op.data() != null && op.data().attributes() != null
                ? new LinkedHashMap<>(op.data().attributes())
                : new LinkedHashMap<>();

        resolveLidsInAttributes(attributes, lidMap);

        if (actingUserId != null) {
            attributes.put("updatedBy", actingUserId);
        }

        Optional<Map<String, Object>> updated = queryEngine.update(definition, id, attributes);
        if (updated.isEmpty()) {
            throw new AtomicOperationException(index, "update", "Record not found: " + type + "/" + id);
        }

        return AtomicResult.of(type, id, null, stripId(updated.get()));
    }

    private AtomicResult executeRemove(AtomicOperation op, int index, Map<String, String> lidMap) {
        String type = op.ref().type();
        String id = resolveId(op.ref(), lidMap, index);
        CollectionDefinition definition = resolveCollection(type, index);

        boolean deleted = queryEngine.delete(definition, id);
        if (!deleted) {
            throw new AtomicOperationException(index, "remove", "Record not found: " + type + "/" + id);
        }

        return AtomicResult.empty();
    }

    private CollectionDefinition resolveCollection(String type, int index) {
        CollectionDefinition def = collectionRegistry.get(type);
        if (def == null) {
            throw new AtomicOperationException(index, "resolve", "Unknown collection type: " + type);
        }
        return def;
    }

    private String resolveId(AtomicOperation.ResourceRef ref, Map<String, String> lidMap, int index) {
        if (ref.id() != null) {
            return ref.id();
        }
        if (ref.lid() != null) {
            String resolved = lidMap.get(ref.lid());
            if (resolved == null) {
                throw new AtomicOperationException(index, "resolve",
                        "Local ID '" + ref.lid() + "' not found — was it created by a prior operation?");
            }
            return resolved;
        }
        throw new AtomicOperationException(index, "resolve", "No id or lid provided in ref");
    }

    /**
     * Resolves lid references within attribute values (for relationship foreign keys).
     */
    private void resolveLidsInAttributes(Map<String, Object> attributes, Map<String, String> lidMap) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getValue() instanceof String value && lidMap.containsKey(value)) {
                attributes.put(entry.getKey(), lidMap.get(value));
            }
        }
    }

    private Map<String, Object> stripId(Map<String, Object> record) {
        Map<String, Object> attrs = new LinkedHashMap<>(record);
        attrs.remove("id");
        return attrs;
    }

    /**
     * Exception thrown when an atomic operation fails.
     * Carries the operation index for error reporting.
     */
    public static class AtomicOperationException extends RuntimeException {
        private final int operationIndex;
        private final String operationType;

        public AtomicOperationException(int operationIndex, String operationType, String message) {
            super(message);
            this.operationIndex = operationIndex;
            this.operationType = operationType;
        }

        public AtomicOperationException(int operationIndex, String operationType, String message, Throwable cause) {
            super(message, cause);
            this.operationIndex = operationIndex;
            this.operationType = operationType;
        }

        public int getOperationIndex() { return operationIndex; }
        public String getOperationType() { return operationType; }
    }
}
