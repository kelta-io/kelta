package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.CompositeUniqueConstraintService;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for composite unique constraints on collection tables.
 *
 * <p>Constraints are enforced at the PostgreSQL level via {@code CREATE UNIQUE
 * INDEX} — no record-level pre-check is required because Postgres rejects
 * duplicate inserts directly and the worker translates the resulting
 * {@code DuplicateKeyException} into a 409 Conflict via
 * {@link io.kelta.runtime.storage.UniqueConstraintViolationException}.
 *
 * <p>This endpoint is reachable through the gateway at
 * {@code /{tenantSlug}/api/admin/collections/{name}/unique-constraints}
 * because {@code /api/admin/**} is a static admin route in the gateway's
 * route table.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/admin/collections/{name}/unique-constraints")
public class CompositeUniqueConstraintController {

    private static final String PERMISSION = "CUSTOMIZE_APPLICATION";

    private static final Logger log = LoggerFactory.getLogger(CompositeUniqueConstraintController.class);

    private final CollectionRegistry collectionRegistry;
    private final CompositeUniqueConstraintService constraintService;
    private final AdminPermissionGuard permissionGuard;

    public CompositeUniqueConstraintController(CollectionRegistry collectionRegistry,
                                                CompositeUniqueConstraintService constraintService,
                                                AdminPermissionGuard permissionGuard) {
        this.collectionRegistry = collectionRegistry;
        this.constraintService = constraintService;
        this.permissionGuard = permissionGuard;
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @PathVariable("name") String collectionName,
                                     @RequestBody Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Collection not found: " + collectionName));
        }

        List<String> fieldNames = extractFieldNames(body);
        if (fieldNames == null || fieldNames.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request must include fieldNames: [...] (or fields: [...])"));
        }

        try {
            CompositeUniqueConstraintService.ConstraintInfo info =
                    constraintService.create(definition, fieldNames);
            log.info("Created composite unique constraint on '{}' over {} (index={})",
                    collectionName, fieldNames, info.indexName());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", toMap(info)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (UniqueConstraintViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Existing rows violate the proposed constraint; remove duplicates first.",
                    "detail", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request, @PathVariable("name") String collectionName) {
        permissionGuard.require(request, PERMISSION);
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Collection not found: " + collectionName));
        }

        List<CompositeUniqueConstraintService.ConstraintInfo> constraints =
                constraintService.list(definition);
        List<Map<String, Object>> data = new ArrayList<>(constraints.size());
        for (CompositeUniqueConstraintService.ConstraintInfo info : constraints) {
            data.add(toMap(info));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @DeleteMapping("/{indexName}")
    public ResponseEntity<?> drop(HttpServletRequest request, @PathVariable("name") String collectionName,
                                   @PathVariable("indexName") String indexName) {
        permissionGuard.require(request, PERMISSION);
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Collection not found: " + collectionName));
        }
        try {
            boolean dropped = constraintService.drop(definition, indexName);
            if (!dropped) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractFieldNames(Map<String, Object> body) {
        if (body == null) return null;
        Object value = body.get("fieldNames");
        if (value == null) value = body.get("fields");
        if (!(value instanceof List<?> list)) return null;
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) return null;
            String s = item.toString();
            if (s.isBlank()) return null;
            result.add(s);
        }
        return result;
    }

    private static Map<String, Object> toMap(CompositeUniqueConstraintService.ConstraintInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indexName", info.indexName());
        m.put("fieldNames", info.fieldNames());
        m.put("columns", info.columns());
        return m;
    }
}
