package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.integration.api.ApiOperation;
import io.kelta.runtime.module.integration.api.ApiSpec;
import io.kelta.runtime.module.integration.api.OpenApiParseException;
import io.kelta.runtime.module.integration.api.OpenApiSpecParser;
import io.kelta.runtime.module.integration.api.ParsedSpec;
import io.kelta.worker.repository.ApiOperationRepository;
import io.kelta.worker.service.api.ApiSpecService;
import io.kelta.worker.service.api.ApiSpecService.ImportRequest;
import io.kelta.worker.service.api.ApiSpecService.ImportResult;
import io.kelta.worker.service.api.ExternalEntityMaterializer;
import io.kelta.worker.service.api.ExternalEntityMaterializer.MaterializeRequest;
import io.kelta.worker.service.api.ExternalEntityMaterializer.MaterializeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the OpenAPI spec library.
 *
 * <p>CRUD on the {@code api-specs} system collection routes through
 * {@code DynamicCollectionRouter} and produces JSON:API responses; this
 * controller adds operation-level reads, search, raw-text fetch, validate-only,
 * and the import (POST/PUT) entry points where the worker invokes the parser.
 */
@RestController
@RequestMapping("/api")
public class ApiSpecController {

    private static final Logger log = LoggerFactory.getLogger(ApiSpecController.class);

    private final ApiSpecService specService;
    private final ApiOperationRepository operationRepository;
    private final OpenApiSpecParser parser;
    private final ExternalEntityMaterializer materializer;

    public ApiSpecController(ApiSpecService specService,
                              ApiOperationRepository operationRepository,
                              OpenApiSpecParser parser,
                              ExternalEntityMaterializer materializer) {
        this.specService = specService;
        this.operationRepository = operationRepository;
        this.parser = parser;
        this.materializer = materializer;
    }

    // ---------------------------------------------------------------------
    // Spec listing + retrieval (parallel to the JSON:API CRUD path; these
    // are convenience endpoints the UI uses when it wants the parsed shape
    // rather than the system-collection wire format)
    // ---------------------------------------------------------------------

    @GetMapping("/api-specs/library")
    public ResponseEntity<?> list() {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        List<Map<String, Object>> specs = specService.listActive(tenantId).stream()
            .map(ApiSpecController::summarize).toList();
        return ResponseEntity.ok(Map.of("data", specs));
    }

    @GetMapping("/api-specs/{id}/details")
    public ResponseEntity<?> get(@PathVariable String id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        return specService.findById(id, tenantId)
            .map(ApiSpecController::summarize)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/api-specs/{id}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> raw(@PathVariable String id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body("No tenant context");
        }
        Optional<ApiSpec> spec = specService.findById(id, tenantId);
        return spec.<ResponseEntity<?>>map(s -> ResponseEntity.ok(s.rawSpec()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api-specs/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        specService.softDelete(id, tenantId, /*userId*/ null);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------
    // Import / re-import
    // ---------------------------------------------------------------------

    @PostMapping("/api-specs/import")
    public ResponseEntity<?> importSpec(@RequestBody ImportRequest body) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        try {
            ImportResult result = specService.importSpec(body, tenantId, /*userId*/ null);
            return ResponseEntity.status(HttpStatus.CREATED).body(toMap(result));
        } catch (OpenApiParseException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "Failed to parse spec", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Spec import failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Spec import failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/api-specs/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, String> body) {
        String raw = body.get("raw");
        String url = body.get("sourceUrl");
        try {
            String content = raw != null && !raw.isBlank()
                ? raw
                : specService.fetchSpec(url);
            ParsedSpec parsed = parser.parse(content);
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "title", parsed.apiTitle() == null ? "" : parsed.apiTitle(),
                "version", parsed.apiVersion() == null ? "" : parsed.apiVersion(),
                "specVersion", parsed.specVersion(),
                "operations", parsed.operations().size(),
                "baseUrl", parsed.baseUrl() == null ? "" : parsed.baseUrl()));
        } catch (OpenApiParseException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------

    @GetMapping("/api-specs/{id}/operations")
    public ResponseEntity<?> listOperations(@PathVariable String id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        List<Map<String, Object>> ops = operationRepository.listBySpec(tenantId, id).stream()
            .map(ApiSpecController::summarizeOp).toList();
        return ResponseEntity.ok(Map.of("data", ops));
    }

    @GetMapping("/api-specs/{specId}/operations/{opId}")
    public ResponseEntity<?> getOperation(@PathVariable String specId,
                                           @PathVariable String opId) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        return operationRepository.findOperation(tenantId, specId, opId)
            .<ResponseEntity<?>>map(op -> ResponseEntity.ok(detailOp(op)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Materializes a GET operation as an external-rest virtual collection (Rec 4f):
     * creates a collection backed by {@code ExternalRestStorageAdapter} (baseUrl +
     * operation path) with fields derived from the operation's response schema, so
     * the external API is queryable via the standard JSON:API surface.
     */
    @PostMapping("/api-specs/{specId}/operations/{opId}/materialize")
    public ResponseEntity<?> materialize(@PathVariable String specId,
                                         @PathVariable String opId,
                                         @RequestBody MaterializeRequest body) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        try {
            MaterializeResult result = materializer.materialize(specId, opId, body, tenantId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("collectionId", result.collectionId());
            out.put("collectionName", result.collectionName());
            out.put("fieldCount", result.fieldCount());
            return ResponseEntity.status(HttpStatus.CREATED).body(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            String rootCause = rootCauseMessage(e);
            log.error("Materialize failed for spec={} op={}: {}", specId, opId, rootCause, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Materialize failed",
                    "message", e.getMessage() == null ? rootCause : e.getMessage(),
                    "cause", rootCause));
        }
    }

    /** Walks the cause chain so the deepest (usually SQL) message surfaces in the response. */
    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    @GetMapping("/api-operations/search")
    public ResponseEntity<?> searchOperations(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "method", required = false) String method,
            @RequestParam(value = "specId", required = false) String specId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        List<Map<String, Object>> ops = operationRepository
            .search(tenantId, q, method, specId, limit).stream()
            .map(ApiSpecController::summarizeOp).toList();
        return ResponseEntity.ok(Map.of("data", ops));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Map<String, Object> summarize(ApiSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", spec.id());
        out.put("name", spec.name());
        out.put("description", spec.description());
        out.put("specVersion", spec.specVersion());
        out.put("apiTitle", spec.apiTitle());
        out.put("apiVersion", spec.apiVersion());
        out.put("baseUrl", spec.baseUrl());
        out.put("servers", spec.servers());
        out.put("securitySchemes", spec.securitySchemes());
        out.put("sourceType", spec.sourceType());
        out.put("sourceUrl", spec.sourceUrl());
        out.put("revision", spec.revision());
        out.put("active", spec.active());
        out.put("lastImportedAt", spec.lastImportedAt() == null
            ? null : spec.lastImportedAt().toString());
        return out;
    }

    private static Map<String, Object> summarizeOp(ApiOperation op) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", op.id());
        out.put("specId", op.specId());
        out.put("operationId", op.operationId());
        out.put("syntheticOpId", op.syntheticOpId());
        out.put("httpMethod", op.httpMethod());
        out.put("pathTemplate", op.pathTemplate());
        out.put("summary", op.summary());
        out.put("tags", op.tags());
        out.put("deprecated", op.deprecated());
        return out;
    }

    private static Map<String, Object> detailOp(ApiOperation op) {
        Map<String, Object> out = summarizeOp(op);
        out.put("description", op.description());
        out.put("parametersSchema", op.parametersSchema());
        out.put("requestBodySchema", op.requestBodySchema());
        out.put("responseSchemas", op.responseSchemas());
        out.put("securityRequired", op.securityRequired());
        return out;
    }

    private static Map<String, Object> toMap(ImportResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spec", summarize(result.spec()));
        out.put("diff", Map.of(
            "added", result.diff().added(),
            "changed", result.diff().changed(),
            "removed", result.diff().removed()));
        return out;
    }
}
