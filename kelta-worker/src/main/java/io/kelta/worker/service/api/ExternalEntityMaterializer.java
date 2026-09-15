package io.kelta.worker.service.api;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.module.integration.api.ApiOperation;
import io.kelta.runtime.module.integration.api.ApiSpec;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.ApiOperationRepository;
import io.kelta.worker.service.api.OpenApiCollectionMapper.DerivedField;
import io.kelta.worker.service.api.OpenApiCollectionMapper.DerivedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Materializes an imported OpenAPI GET-list operation as an <b>external-rest
 * virtual collection</b> (Rec 4f). The new collection's {@code adapterConfig}
 * points {@code ExternalRestStorageAdapter} at the spec's base URL + the
 * operation path, and its fields are derived from the operation's response schema
 * — so the external API becomes queryable through the platform's standard
 * JSON:API / includes / flows / MCP surface with no physical table.
 *
 * <p>Creation goes through {@link QueryEngine#create} against the {@code collections}
 * and {@code fields} system collections — the same write path the admin API and MCP
 * tools use — so the collection-config NATS broadcast + lifecycle init fire
 * normally. For an external collection, storage init and field-add are no-ops
 * (the dispatcher routes schema ops to the REST adapter), so no DDL runs.
 */
@Service
public class ExternalEntityMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ExternalEntityMaterializer.class);

    /** Collection names must be a safe identifier (also the route segment). */
    private static final Pattern COLLECTION_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** Reserved system field names (case-insensitive) — cannot be user fields. */
    private static final java.util.Set<String> RESERVED_FIELD_NAMES =
            java.util.Set.of("id", "createdat", "updatedat", "createdby", "updatedby", "tenantid");

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final ApiSpecService specService;
    private final ApiOperationRepository operationRepository;
    private final OpenApiCollectionMapper mapper;

    public ExternalEntityMaterializer(QueryEngine queryEngine,
                                      CollectionRegistry collectionRegistry,
                                      ApiSpecService specService,
                                      ApiOperationRepository operationRepository,
                                      OpenApiCollectionMapper mapper) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.specService = specService;
        this.operationRepository = operationRepository;
        this.mapper = mapper;
    }

    /** Request body for materialization. Only {@code collectionName} is required. */
    public record MaterializeRequest(
            String collectionName,
            String displayName,
            String dataPath,
            String idAttribute,
            String credentialRef
    ) {}

    public record MaterializeResult(String collectionId, String collectionName, int fieldCount) {}

    /**
     * @param specId    the imported spec id
     * @param opId      the operation's {@code syntheticOpId}
     * @param req       materialization options
     * @param tenantId  the tenant
     * @throws IllegalArgumentException for a bad request (validation / not-found / non-GET)
     */
    public MaterializeResult materialize(String specId, String opId,
                                         MaterializeRequest req, String tenantId) {
        if (req == null || req.collectionName() == null
                || !COLLECTION_NAME.matcher(req.collectionName()).matches()) {
            throw new IllegalArgumentException(
                    "collectionName is required and must match ^[a-z][a-z0-9_]*$");
        }

        return TenantContext.callWithTenant(tenantId, () -> {
            ApiSpec spec = specService.findById(specId, tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("API spec not found: " + specId));
            if (spec.baseUrl() == null || spec.baseUrl().isBlank()) {
                throw new IllegalArgumentException(
                        "Spec has no base URL — cannot back an external-rest collection");
            }

            ApiOperation op = operationRepository.findOperation(tenantId, specId, opId)
                    .orElseThrow(() -> new IllegalArgumentException("Operation not found: " + opId));
            if (!"GET".equalsIgnoreCase(op.httpMethod())) {
                throw new IllegalArgumentException(
                        "Only GET operations can back a virtual collection (was " + op.httpMethod() + ")");
            }

            DerivedSchema derived = mapper.map(op.responseSchemas());

            String name = req.collectionName();
            if (collectionRegistry.get(name) != null) {
                throw new IllegalArgumentException("Collection already exists: " + name);
            }

            String dataPath = req.dataPath() != null ? req.dataPath() : derived.dataPath();
            String idAttribute = req.idAttribute() != null ? req.idAttribute() : derived.idAttribute();

            Map<String, Object> adapterConfig = new LinkedHashMap<>();
            adapterConfig.put("adapterType", "external-rest");
            adapterConfig.put("baseUrl", spec.baseUrl());
            adapterConfig.put("path", op.pathTemplate());
            if (dataPath != null && !dataPath.isBlank()) {
                adapterConfig.put("dataPath", dataPath);
            }
            adapterConfig.put("idAttribute", idAttribute);
            if (req.credentialRef() != null && !req.credentialRef().isBlank()) {
                adapterConfig.put("credentialRef", req.credentialRef());
            }

            CollectionDefinition collectionsDef = collectionRegistry.get("collections");
            CollectionDefinition fieldsDef = collectionRegistry.get("fields");
            if (collectionsDef == null || fieldsDef == null) {
                throw new IllegalStateException("System collections not initialized");
            }

            Map<String, Object> collectionData = new LinkedHashMap<>();
            // The storage adapter writes tenant_id for a tenant-scoped system
            // collection only when "tenantId" is present in the record. The JSON:API
            // layer injects it on the HTTP path; a direct queryEngine.create must set
            // it itself, or the NOT NULL collection.tenant_id is violated.
            collectionData.put("tenantId", tenantId);
            collectionData.put("name", name);
            collectionData.put("displayName",
                    req.displayName() != null && !req.displayName().isBlank()
                            ? req.displayName()
                            : defaultDisplayName(name, op));
            collectionData.put("path", "/api/" + name);
            collectionData.put("active", true);
            collectionData.put("systemCollection", false);
            collectionData.put("currentVersion", 1);
            collectionData.put("adapterConfig", adapterConfig);

            Map<String, Object> created = queryEngine.create(collectionsDef, collectionData);
            String collectionId = String.valueOf(created.get("id"));

            int order = 0;
            int fieldsCreated = 0;
            for (DerivedField field : derived.fields()) {
                // System field names (id, createdAt, …) are reserved and map to
                // built-in columns — the external adapter handles the id via
                // adapterConfig.idAttribute, so skip them rather than fail validation.
                if (isReserved(field.name())) {
                    continue;
                }
                Map<String, Object> fieldData = new LinkedHashMap<>();
                // fields is tenant-scoped too (KLT-206): same direct-create rule as above.
                fieldData.put("tenantId", tenantId);
                fieldData.put("collectionId", collectionId);
                fieldData.put("name", field.name());
                fieldData.put("displayName", field.name());
                fieldData.put("type", field.type().name());
                fieldData.put("fieldOrder", order++);
                fieldData.put("active", true);
                queryEngine.create(fieldsDef, fieldData);
                fieldsCreated++;
            }

            log.info("Materialized external-rest collection '{}' (id={}) from spec={} op={} with {} fields",
                    name, collectionId, specId, opId, fieldsCreated);
            return new MaterializeResult(collectionId, name, fieldsCreated);
        });
    }

    private boolean isReserved(String fieldName) {
        return RESERVED_FIELD_NAMES.contains(fieldName.toLowerCase());
    }

    private String defaultDisplayName(String name, ApiOperation op) {
        if (op.summary() != null && !op.summary().isBlank()) {
            return op.summary();
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
