package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.registry.CollectionOnDemandLoader;
import io.kelta.runtime.registry.CollectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves a collection's field schema: {@code GET /api/collections/{name}/schema}.
 *
 * <p>Answers for <b>system</b> collections as well as tenant ones, which is the point of the
 * endpoint — an API client authoring metadata (page layouts, list views, dashboard components,
 * UI pages) otherwise has no way to learn attribute names, types, defaults, enum values or
 * reference targets short of reading {@code SystemCollectionDefinitions} source.
 *
 * <p>Authorization: this rides the existing static {@code /api/collections/**} gateway route, so
 * only {@code API_ACCESS} is checked. That matches the metadata already readable through
 * {@code GET /api/collections?include=fields} on the same prefix — this endpoint exposes the same
 * schema, in one response, for collections whose fields have no rows in the {@code field} table.
 *
 * <p>The mapping spells out its literal segments deliberately: {@code DynamicCollectionRouter}
 * maps all-variable GETs up to four segments under {@code /api}, and a less specific pattern here
 * would lose every request to it and 404 as a record read (see {@code concerns.md} → Fragile Areas).
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/collections")
public class CollectionSchemaController {

    private static final Logger logger = LoggerFactory.getLogger(CollectionSchemaController.class);

    private final CollectionRegistry registry;

    /**
     * Optional on-demand loader, mirroring {@code DynamicCollectionRouter}: a tenant collection
     * this pod has not loaded yet must not read as "no such collection".
     */
    private CollectionOnDemandLoader onDemandLoader;

    public CollectionSchemaController(CollectionRegistry registry) {
        this.registry = registry;
    }

    @Autowired(required = false)
    public void setOnDemandLoader(CollectionOnDemandLoader onDemandLoader) {
        this.onDemandLoader = onDemandLoader;
    }

    /**
     * Returns the schema of one collection.
     *
     * @param name     the collection name, as it appears in {@code /api/{name}}
     * @param tenantId tenant from the gateway-attached header, used only for on-demand loading
     * @return {@code {name, displayName, systemCollection, fields:[...]}}, or 404 if unknown
     */
    @GetMapping("/{name}/schema")
    public ResponseEntity<Map<String, Object>> schema(
            @PathVariable("name") String name,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        CollectionDefinition definition = resolve(name, tenantId);
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(JsonApiResponseBuilder.error(
                    "404", "COLLECTION_NOT_FOUND", "Not Found",
                    "No collection named '" + name + "'"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", definition.name());
        body.put("displayName", definition.displayName());
        body.put("systemCollection", definition.systemCollection());

        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldDefinition field : definition.fields()) {
            fields.add(describe(field));
        }
        body.put("fields", fields);

        return ResponseEntity.ok(body);
    }

    private CollectionDefinition resolve(String name, String tenantId) {
        CollectionDefinition definition = registry.get(name);
        if (definition != null || onDemandLoader == null) {
            return definition;
        }
        try {
            return onDemandLoader.load(name, tenantId);
        } catch (Exception e) {
            logger.warn("On-demand load failed for collection '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> describe(FieldDefinition field) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", field.name());
        out.put("type", field.type().name());
        out.put("required", !field.nullable());
        out.put("isRelationship", field.type().isRelationship());
        out.put("description", field.description());

        if (field.defaultValue() != null) {
            out.put("default", field.defaultValue());
        }
        if (field.enumValues() != null && !field.enumValues().isEmpty()) {
            out.put("enum", field.enumValues());
        }
        ReferenceConfig reference = field.referenceConfig();
        if (reference != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("target", reference.targetCollection());
            ref.put("targetField", reference.targetField());
            if (reference.relationshipType() != null) {
                ref.put("relationshipType", reference.relationshipType());
            }
            if (reference.relationshipName() != null) {
                ref.put("relationshipName", reference.relationshipName());
            }
            out.put("reference", ref);
        }
        return out;
    }
}
