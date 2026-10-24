package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.FilterOperator;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates an OpenAPI 3.0 specification from collection definitions.
 *
 * <p>System collections are documented alongside tenant ones: they are served by the same
 * {@code DynamicCollectionRouter} and are the collections an API client authoring metadata
 * (layouts, list views, dashboards, UI pages) actually writes to.
 *
 * <p>Status codes follow {@code GlobalExceptionHandler}: schema/field validation fails with
 * <b>400</b>, a unique-constraint clash with 409, and only custom (formula) validation rules
 * fail with 422.
 *
 * <p>Paths are written as the worker serves them ({@code /api/...}); the tenant slug the gateway
 * strips goes on {@code servers[0].url} instead, so a generated client calls
 * {@code <host>/<slug>/api/...} without the slug repeated in every path.
 *
 * @since 1.0.0
 */
@Service
public class OpenApiGenerator {

    private static final Map<FieldType, Map<String, String>> TYPE_MAPPING = Map.ofEntries(
            Map.entry(FieldType.STRING, Map.of("type", "string")),
            Map.entry(FieldType.INTEGER, Map.of("type", "integer")),
            Map.entry(FieldType.LONG, Map.of("type", "integer", "format", "int64")),
            Map.entry(FieldType.DOUBLE, Map.of("type", "number", "format", "double")),
            Map.entry(FieldType.BOOLEAN, Map.of("type", "boolean")),
            Map.entry(FieldType.DATE, Map.of("type", "string", "format", "date")),
            Map.entry(FieldType.DATETIME, Map.of("type", "string", "format", "date-time")),
            // Any JSON value: system collections store arrays as well as objects here.
            Map.entry(FieldType.JSON, Map.of())
    );

    /** Canonical filter operators, read off the enum so the document cannot drift from the parser. */
    private static final List<String> FILTER_OPERATORS = Arrays.stream(FilterOperator.values())
            .map(op -> op.name().toLowerCase(Locale.ROOT))
            .toList();

    private static final String ERROR_SCHEMA = "ErrorDocument";
    private static final String SYSTEM_MAINTAINED_NOTE = "System-maintained: set by flows and server-side"
            + " code; an API create that supplies it or an update that changes it returns 400.";

    /**
     * Generates an OpenAPI 3.0 specification from the given collections, without a tenant prefix.
     */
    public Map<String, Object> generate(Collection<CollectionDefinition> collections, String serverUrl) {
        return generate(collections, serverUrl, null);
    }

    /**
     * Generates an OpenAPI 3.0 specification from the given collections. A non-blank
     * {@code tenantSlug} is appended to {@code serverUrl} (or becomes the relative server
     * {@code /<slug>} when no URL is configured).
     */
    public Map<String, Object> generate(Collection<CollectionDefinition> collections, String serverUrl,
                                        String tenantSlug) {
        var spec = new LinkedHashMap<String, Object>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of(
                "title", "Kelta Platform API",
                "description", "Auto-generated API documentation from collection schemas",
                "version", "1.0.0"
        ));

        String server = serverUrl(serverUrl, tenantSlug);
        if (server != null) {
            spec.put("servers", List.of(Map.of("url", server)));
        }

        var securitySchemes = new LinkedHashMap<String, Object>();
        securitySchemes.put("bearerAuth", Map.of(
                "type", "http",
                "scheme", "bearer",
                "bearerFormat", "JWT",
                "description", "Access token (JWT) issued by the tenant's OIDC sign-in"
        ));
        securitySchemes.put("patAuth", Map.of(
                "type", "http",
                "scheme", "bearer",
                "description", "Personal access token: the klt_-prefixed token, sent as"
                        + " Authorization: Bearer klt_..."
        ));
        var components = new LinkedHashMap<String, Object>();
        components.put("securitySchemes", securitySchemes);
        components.put("schemas", generateSchemas(collections));
        spec.put("components", components);
        // Separate requirement objects are alternatives: either scheme alone authorizes a call.
        spec.put("security", List.of(Map.of("bearerAuth", List.of()), Map.of("patAuth", List.of())));

        spec.put("paths", generatePaths(collections));

        return spec;
    }

    private static String serverUrl(String serverUrl, String tenantSlug) {
        String base = serverUrl == null ? "" : serverUrl.strip().replaceAll("/+$", "");
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return base.isEmpty() ? null : base;
        }
        return base + "/" + tenantSlug.strip();
    }

    private Map<String, Object> generatePaths(Collection<CollectionDefinition> collections) {
        var paths = new LinkedHashMap<String, Object>();

        for (CollectionDefinition col : collections) {
            String basePath = "/api/" + col.name();

            var listOps = new LinkedHashMap<String, Object>();
            listOps.put("get", listOperation(col));
            if (!col.readOnly()) {
                listOps.put("post", createOperation(col));
            }
            paths.put(basePath, listOps);

            var itemOps = new LinkedHashMap<String, Object>();
            itemOps.put("get", getByIdOperation(col));
            if (!col.readOnly()) {
                itemOps.put("put", updateOperation(col, "put"));
                itemOps.put("patch", updateOperation(col, "patch"));
                itemOps.put("delete", deleteOperation(col));
            }
            paths.put(basePath + "/{id}", itemOps);

            if (col.apiConfig() == null || col.apiConfig().listEnabled()) {
                paths.put(basePath + "/aggregate", Map.of("get", aggregateOperation(col)));
                paths.put(basePath + "/latest", Map.of("get", latestOperation(col)));
            }
        }

        paths.put("/api/operations", Map.of("post", atomicOperationsEndpoint()));

        return paths;
    }

    private Map<String, Object> listOperation(CollectionDefinition col) {
        var op = new LinkedHashMap<String, Object>();
        op.put("summary", "List " + col.displayName());
        op.put("description", col.description() != null ? col.description() : "");
        op.put("tags", List.of(col.displayName()));
        op.put("parameters", List.of(
                queryParam("page[number]", "integer", "Page number (1-based, default 1)"),
                queryParam("page[size]", "integer",
                        "Page size (default 20, clamped to 200 — check meta.pageSizeClamped)"),
                queryParam("sort", "string", "Sort field (prefix with - for descending)"),
                queryParam("include", "string", "Related resources to include (comma-separated)"),
                filterParam(),
                sparseFieldsetParam()
        ));
        op.put("responses", responses(
                response("200", "List of " + col.name() + " resources", schemaRef(col.name() + "ListResponse")),
                errorResponse("400", "Invalid query parameter (unknown filter operator, malformed"
                        + " filter, or unsortable field)")
        ));
        return op;
    }

    private Map<String, Object> getByIdOperation(CollectionDefinition col) {
        var op = new LinkedHashMap<String, Object>();
        op.put("summary", "Get " + col.displayName() + " by ID");
        op.put("tags", List.of(col.displayName()));
        op.put("parameters", List.of(pathParam("id", "Resource ID (UUID)"), sparseFieldsetParam()));
        op.put("responses", responses(
                response("200", "Single " + col.name() + " resource", schemaRef(col.name() + "Response")),
                errorResponse("404", "Not found")
        ));
        return op;
    }

    /** Mirrors runtime-core {@code CollectionAggregateController}. */
    private Map<String, Object> aggregateOperation(CollectionDefinition col) {
        var group = new LinkedHashMap<String, Object>();
        group.put("type", "object");
        group.put("properties", Map.of(
                "key", Map.of("type", "string", "description", "Distinct value of the groupBy field"),
                "count", Map.of("type", "integer", "format", "int64"),
                "avg", Map.of("type", "number", "format", "double", "nullable", true,
                        "description", "Average of the avg field, one decimal; null when not requested"
                                + " or no numeric value in the group")
        ));
        var body = Map.<String, Object>of("type", "object", "properties", Map.of(
                "totalCount", Map.of("type", "integer", "description", "Rows aggregated (at most 1000)"),
                "groups", Map.of("type", "array", "items", group,
                        "description", "One entry per distinct groupBy value, by count descending")
        ));

        var op = new LinkedHashMap<String, Object>();
        op.put("summary", "Aggregate " + col.displayName());
        op.put("description", "Count (and optionally average) per distinct value of a field, over"
                + " up to 1000 filtered rows. Returns only counts and averages, never rows.");
        op.put("tags", List.of(col.displayName()));
        op.put("parameters", List.of(
                requiredQueryParam("groupBy", "Field to group by"),
                queryParam("avg", "string", "Numeric field to average per group"),
                filterParam()
        ));
        op.put("responses", responses(
                jsonResponse("200", "Groups of " + col.name() + " records", body),
                Map.entry("400", Map.of("description", "groupBy is missing")),
                Map.entry("404", Map.of("description", "Unknown collection"))
        ));
        return op;
    }

    /** Mirrors runtime-core {@code LatestRecordController}. */
    private Map<String, Object> latestOperation(CollectionDefinition col) {
        var record = new LinkedHashMap<String, Object>();
        record.put("type", "object");
        record.put("nullable", true);
        record.put("description", "The requested fields of the newest matching record, plus"
                + " recordedAt (its createdAt); null when nothing matches");
        record.put("properties", Map.of("recordedAt", Map.of("type", "string", "format", "date-time")));
        record.put("additionalProperties", true);

        var op = new LinkedHashMap<String, Object>();
        op.put("summary", "Latest " + col.displayName());
        op.put("description", "The requested fields of the most recently created matching record.");
        op.put("tags", List.of(col.displayName()));
        op.put("parameters", List.of(
                requiredQueryParam("fields", "Comma-separated field names to return"),
                filterParam()
        ));
        op.put("responses", responses(
                jsonResponse("200", "Latest " + col.name() + " record",
                        Map.of("type", "object", "properties", Map.of("record", record))),
                Map.entry("400", Map.of("description", "fields is missing or empty")),
                Map.entry("404", Map.of("description", "Unknown collection"))
        ));
        return op;
    }

    private Map<String, Object> filterParam() {
        return queryParam("filter[{field}][{op}]", "string",
                "Filter on a field. {op} is one of: " + String.join(" ", FILTER_OPERATORS)
                        + ". `in` accepts the parameter repeated once per value; the shorthand"
                        + " filter[{field}]=value means eq.");
    }

    private Map<String, Object> sparseFieldsetParam() {
        return queryParam("fields[{type}]", "string",
                "Sparse fieldset: comma-separated attribute/relationship names to return"
                        + " (id is always included). The bare form fields=a,b is also accepted;"
                        + " {type} is not validated against the resource type.");
    }

    private Map<String, Object> createOperation(CollectionDefinition col) {
        var op = new LinkedHashMap<String, Object>();
        op.put("summary", "Create " + col.displayName());
        op.put("tags", List.of(col.displayName()));
        op.put("requestBody", requestBody(col));
        op.put("responses", responses(
                response("201", "Created", schemaRef(col.name() + "Response")),
                errorResponse("400", "Validation error — a field failed schema validation"
                        + " (required, type, length, pattern, enum)"),
                errorResponse("409", "Unique constraint violation"),
                errorResponse("422", "A custom validation rule (formula) rejected the record")
        ));
        return op;
    }

    private Map<String, Object> updateOperation(CollectionDefinition col, String method) {
        var op = new LinkedHashMap<String, Object>();
        op.put("summary", ("put".equals(method) ? "Replace " : "Update ") + col.displayName());
        op.put("tags", List.of(col.displayName()));
        op.put("parameters", List.of(pathParam("id", "Resource ID (UUID)")));
        op.put("requestBody", requestBody(col));
        op.put("responses", responses(
                response("200", "Updated", schemaRef(col.name() + "Response")),
                errorResponse("400", "Validation error — a field failed schema validation"
                        + " (required, type, length, pattern, enum)"),
                errorResponse("404", "Not found"),
                errorResponse("409", "Unique constraint violation"),
                errorResponse("422", "A custom validation rule (formula) rejected the record")
        ));
        return op;
    }

    private Map<String, Object> deleteOperation(CollectionDefinition col) {
        var op = new LinkedHashMap<String, Object>();
        op.put("summary", "Delete " + col.displayName());
        op.put("tags", List.of(col.displayName()));
        op.put("parameters", List.of(pathParam("id", "Resource ID (UUID)")));
        op.put("responses", responses(
                Map.entry("204", Map.of("description", "Deleted")),
                errorResponse("404", "Not found")
        ));
        return op;
    }

    private Map<String, Object> requestBody(CollectionDefinition col) {
        return Map.of(
                "required", true,
                "content", Map.of("application/vnd.api+json", Map.of(
                        "schema", schemaRef(col.name() + "Request")
                ))
        );
    }

    private Map<String, Object> atomicOperationsEndpoint() {
        var op = new LinkedHashMap<String, Object>();
        op.put("summary", "Execute Atomic Operations (bulk CRUD)");
        op.put("description", "JSON:API Atomic Operations extension — execute multiple"
                + " create/update/delete operations in a single transaction");
        op.put("tags", List.of("Atomic Operations"));
        op.put("requestBody", Map.of(
                "required", true,
                "content", Map.of("application/vnd.api+json", Map.of(
                        "schema", Map.of("type", "object", "properties", Map.of(
                                "atomic:operations", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "object", "properties", Map.of(
                                                "op", Map.of("type", "string",
                                                        "enum", List.of("add", "update", "remove")),
                                                "ref", Map.of("type", "object"),
                                                "data", Map.of("type", "object")
                                        ))
                                )
                        ))
                ))
        ));
        op.put("responses", responses(
                Map.entry("200", Map.of("description", "All operations succeeded")),
                errorResponse("400", "Malformed request — missing atomic:operations, or an operation"
                        + " without a resolvable type/id/lid"),
                errorResponse("422", "An operation failed — the whole batch was rolled back")
        ));
        return op;
    }

    // =========================================================================
    // Schemas
    // =========================================================================

    private Map<String, Object> generateSchemas(Collection<CollectionDefinition> collections) {
        var schemas = new LinkedHashMap<String, Object>();
        for (CollectionDefinition col : collections) {
            schemas.put(col.name() + "Request", generateRequestSchema(col));
            schemas.put(col.name() + "Resource", generateResourceSchema(col));
            schemas.put(col.name() + "Response", Map.of(
                    "type", "object",
                    "properties", Map.of("data", schemaRef(col.name() + "Resource"))
            ));
            schemas.put(col.name() + "ListResponse", generateListResponseSchema(col));
        }
        schemas.put(ERROR_SCHEMA, errorSchema());
        return schemas;
    }

    private Map<String, Object> generateRequestSchema(CollectionDefinition col) {
        var attributes = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (FieldDefinition field : col.fields()) {
            if (field.serverOnly()) {
                continue; // server-only: a caller-supplied value is ignored
            }
            attributes.put(field.name(), mapFieldType(field));
            if (!field.nullable() && field.defaultValue() == null && !field.systemMaintained()) {
                required.add(field.name());
            }
        }

        var attributesSchema = new LinkedHashMap<String, Object>();
        attributesSchema.put("type", "object");
        attributesSchema.put("properties", attributes);
        if (!required.isEmpty()) {
            attributesSchema.put("required", required);
        }

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "data", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "type", Map.of("type", "string", "example", col.name()),
                                        "attributes", attributesSchema
                                )
                        )
                )
        );
    }

    /**
     * The read-side resource object. Relationship fields (those with a reference config) are
     * emitted under {@code relationships}, every other field under {@code attributes} — the split
     * the router applies when it serializes a record.
     */
    private Map<String, Object> generateResourceSchema(CollectionDefinition col) {
        var attributes = new LinkedHashMap<String, Object>();
        var relationships = new LinkedHashMap<String, Object>();
        for (FieldDefinition field : col.fields()) {
            if (field.serverOnly()) {
                continue; // server-only: never serialized
            }
            if (field.referenceConfig() != null) {
                relationships.put(field.name(), relationshipSchema(field));
            } else {
                attributes.put(field.name(), mapFieldType(field));
            }
        }

        var properties = new LinkedHashMap<String, Object>();
        properties.put("type", Map.of("type", "string", "example", col.name()));
        properties.put("id", Map.of("type", "string", "description", "Resource ID (UUID)"));
        properties.put("attributes", Map.of("type", "object", "properties", attributes));
        if (!relationships.isEmpty()) {
            properties.put("relationships", Map.of("type", "object", "properties", relationships));
        }

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        if (col.description() != null && !col.description().isBlank()) {
            schema.put("description", col.description());
        }
        schema.put("properties", properties);
        return schema;
    }

    private Map<String, Object> relationshipSchema(FieldDefinition field) {
        var identifier = new LinkedHashMap<String, Object>();
        identifier.put("type", "object");
        identifier.put("nullable", true);
        identifier.put("properties", Map.of(
                "type", Map.of("type", "string",
                        "example", field.referenceConfig().targetCollection()),
                "id", Map.of("type", "string")
        ));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        if (field.description() != null && !field.description().isBlank()) {
            schema.put("description", field.description());
        }
        schema.put("properties", Map.of("data", identifier));
        return schema;
    }

    private Map<String, Object> generateListResponseSchema(CollectionDefinition col) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("data", Map.of("type", "array", "items", schemaRef(col.name() + "Resource")));
        properties.put("meta", Map.of("type", "object", "properties", Map.of(
                "totalCount", Map.of("type", "integer"),
                "pageNumber", Map.of("type", "integer"),
                "pageSize", Map.of("type", "integer"),
                "totalPages", Map.of("type", "integer"),
                "pageSizeClamped", Map.of("type", "boolean",
                        "description", "Present when the requested page[size] exceeded 200")
        )));
        properties.put("links", Map.of("type", "object"));
        return Map.of("type", "object", "properties", properties);
    }

    private Map<String, Object> errorSchema() {
        var error = new LinkedHashMap<String, Object>();
        error.put("type", "object");
        error.put("properties", Map.of(
                "status", Map.of("type", "string"),
                "code", Map.of("type", "string"),
                "title", Map.of("type", "string"),
                "detail", Map.of("type", "string"),
                "source", Map.of("type", "object"),
                "meta", Map.of("type", "object")
        ));
        return Map.of(
                "type", "object",
                "properties", Map.of("errors", Map.of("type", "array", "items", error))
        );
    }

    private Map<String, Object> mapFieldType(FieldDefinition field) {
        var mapped = new LinkedHashMap<String, Object>();
        mapped.putAll(TYPE_MAPPING.getOrDefault(field.type(), Map.of("type", "string")));
        if (field.description() != null && !field.description().isBlank()) {
            mapped.put("description", field.description());
        }
        if (field.systemMaintained()) {
            mapped.put("readOnly", true);
            Object description = mapped.get("description");
            mapped.put("description", description == null
                    ? SYSTEM_MAINTAINED_NOTE : description + " " + SYSTEM_MAINTAINED_NOTE);
        }
        if (field.nullable()) {
            mapped.put("nullable", true);
        }
        if (field.enumValues() != null && !field.enumValues().isEmpty()) {
            mapped.put("enum", field.enumValues());
        }
        if (field.defaultValue() != null) {
            mapped.put("default", field.defaultValue());
        }
        return mapped;
    }

    // =========================================================================
    // Small builders
    // =========================================================================

    @SafeVarargs
    private Map<String, Object> responses(Map.Entry<String, Object>... entries) {
        var responses = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : entries) {
            responses.put(entry.getKey(), entry.getValue());
        }
        return responses;
    }

    private Map.Entry<String, Object> response(String status, String description, Map<String, Object> schema) {
        return Map.entry(status, Map.of(
                "description", description,
                "content", Map.of("application/vnd.api+json", Map.of("schema", schema))
        ));
    }

    private Map.Entry<String, Object> jsonResponse(String status, String description, Map<String, Object> schema) {
        return Map.entry(status, Map.of(
                "description", description,
                "content", Map.of("application/json", Map.of("schema", schema))
        ));
    }

    private Map.Entry<String, Object> errorResponse(String status, String description) {
        return response(status, description, schemaRef(ERROR_SCHEMA));
    }

    private Map<String, Object> schemaRef(String name) {
        return Map.of("$ref", "#/components/schemas/" + name);
    }

    private Map<String, Object> queryParam(String name, String type, String description) {
        return Map.of("name", name, "in", "query", "required", false,
                "schema", Map.of("type", type), "description", description);
    }

    private Map<String, Object> requiredQueryParam(String name, String description) {
        return Map.of("name", name, "in", "query", "required", true,
                "schema", Map.of("type", "string"), "description", description);
    }

    private Map<String, Object> pathParam(String name, String description) {
        return Map.of("name", name, "in", "path", "required", true,
                "schema", Map.of("type", "string"), "description", description);
    }
}
