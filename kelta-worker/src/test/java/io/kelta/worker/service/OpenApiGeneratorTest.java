package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.FilterOperator;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenApiGenerator Tests")
class OpenApiGeneratorTest {

    private OpenApiGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new OpenApiGenerator();
    }

    private CollectionDefinition createCollection(String name, String displayName, boolean readOnly, boolean system) {
        return CollectionDefinition.builder()
                .name(name)
                .displayName(displayName)
                .description("Test collection: " + name)
                .addField(FieldDefinition.requiredString("name"))
                .addField(new FieldDefinition("email", FieldType.STRING, true, false, false, null, null, null, null, null))
                .addField(new FieldDefinition("age", FieldType.INTEGER, true, false, false, null, null, null, null, null))
                .addField(new FieldDefinition("active", FieldType.BOOLEAN, false, false, false, null, null, null, null, null))
                .addField(new FieldDefinition("birthDate", FieldType.DATE, true, false, false, null, null, null, null, null))
                .readOnly(readOnly)
                .systemCollection(system)
                .build();
    }

    @Test
    @DisplayName("Should generate valid OpenAPI 3.0 structure")
    @SuppressWarnings("unchecked")
    void shouldGenerateValidStructure() {
        var collections = List.of(createCollection("contacts", "Contacts", false, false));
        Map<String, Object> spec = generator.generate(collections, "https://api.kelta.io");

        assertThat(spec.get("openapi")).isEqualTo("3.0.3");
        assertThat(spec.get("info")).isNotNull();
        assertThat(spec.get("paths")).isNotNull();
        assertThat(spec.get("components")).isNotNull();
        assertThat(spec.get("security")).isNotNull();

        // Server URL
        List<Map<String, Object>> servers = (List<Map<String, Object>>) spec.get("servers");
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).get("url")).isEqualTo("https://api.kelta.io");
    }

    @Test
    @DisplayName("Should include CRUD paths for each collection")
    @SuppressWarnings("unchecked")
    void shouldIncludeCrudPaths() {
        var collections = List.of(createCollection("contacts", "Contacts", false, false));
        Map<String, Object> spec = generator.generate(collections, null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertThat(paths).containsKey("/api/contacts");
        assertThat(paths).containsKey("/api/contacts/{id}");

        Map<String, Object> listPath = (Map<String, Object>) paths.get("/api/contacts");
        assertThat(listPath).containsKey("get");
        assertThat(listPath).containsKey("post");

        Map<String, Object> itemPath = (Map<String, Object>) paths.get("/api/contacts/{id}");
        assertThat(itemPath).containsKeys("get", "put", "patch", "delete");
    }

    @Test
    @DisplayName("Should only include GET for read-only collections")
    @SuppressWarnings("unchecked")
    void shouldOnlyGetForReadOnly() {
        var collections = List.of(createCollection("audit-logs", "Audit Logs", true, false));
        Map<String, Object> spec = generator.generate(collections, null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> listPath = (Map<String, Object>) paths.get("/api/audit-logs");
        assertThat(listPath).containsKey("get");
        assertThat(listPath).doesNotContainKey("post");

        Map<String, Object> itemPath = (Map<String, Object>) paths.get("/api/audit-logs/{id}");
        assertThat(itemPath).containsKey("get");
        assertThat(itemPath).doesNotContainKeys("put", "patch", "delete");
    }

    @Test
    @DisplayName("Should document system collections alongside tenant ones")
    @SuppressWarnings("unchecked")
    void shouldIncludeSystemCollections() {
        var collections = List.of(
                createCollection("contacts", "Contacts", false, false),
                SystemCollectionDefinitions.byName().get("list-views")
        );
        Map<String, Object> spec = generator.generate(collections, null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertThat(paths).containsKey("/api/contacts");
        assertThat(paths).containsKey("/api/list-views");
        assertThat(paths).containsKey("/api/list-views/{id}");

        Map<String, Object> schemas = (Map<String, Object>) components(spec).get("schemas");
        assertThat(schemas).containsKeys("list-viewsRequest", "list-viewsResponse",
                "list-viewsListResponse");
    }

    @Test
    @DisplayName("Should include security scheme")
    @SuppressWarnings("unchecked")
    void shouldIncludeSecurityScheme() {
        var collections = List.of(createCollection("contacts", "Contacts", false, false));
        Map<String, Object> spec = generator.generate(collections, null);

        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> securitySchemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(securitySchemes).containsKey("bearerAuth");
    }

    @Test
    @DisplayName("Should include Atomic Operations endpoint")
    @SuppressWarnings("unchecked")
    void shouldIncludeAtomicOperations() {
        var collections = List.of(createCollection("contacts", "Contacts", false, false));
        Map<String, Object> spec = generator.generate(collections, null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertThat(paths).containsKey("/api/operations");
    }

    @Test
    @DisplayName("Should generate schemas for collection fields")
    @SuppressWarnings("unchecked")
    void shouldGenerateFieldSchemas() {
        var collections = List.of(createCollection("contacts", "Contacts", false, false));
        Map<String, Object> spec = generator.generate(collections, null);

        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).containsKey("contactsRequest");
    }

    @Test
    @DisplayName("List operations document filter and sparse-fieldset parameters")
    @SuppressWarnings("unchecked")
    void listOperationsDocumentFilterAndFieldsParameters() {
        var collections = List.of(
                createCollection("contacts", "Contacts", false, false),
                SystemCollectionDefinitions.byName().get("list-views"));
        Map<String, Object> spec = generator.generate(collections, null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        for (String path : List.of("/api/contacts", "/api/list-views")) {
            Map<String, Object> list = (Map<String, Object>) paths.get(path);
            Map<String, Object> get = (Map<String, Object>) list.get("get");
            List<Map<String, Object>> params = (List<Map<String, Object>>) get.get("parameters");

            Map<String, Object> filter = paramNamed(params, "filter[{field}][{op}]");
            assertThat((String) filter.get("description"))
                    .contains("eq", "neq", "gt", "gte", "lt", "lte", "isnull", "contains",
                            "starts", "ends", "icontains", "istarts", "iends", "ieq", "in");

            assertThat(paramNamed(params, "fields[{type}]")).isNotNull();
            assertThat(paramNamed(params, "page[size]")).isNotNull();
        }
    }

    @Test
    @DisplayName("Every documented filter operator is one the parser accepts")
    @SuppressWarnings("unchecked")
    void documentedFilterOperatorsMatchTheParser() {
        Map<String, Object> spec = generator.generate(
                List.of(createCollection("contacts", "Contacts", false, false)), null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> get = (Map<String, Object>) ((Map<String, Object>)
                paths.get("/api/contacts")).get("get");
        String description = (String) paramNamed(
                (List<Map<String, Object>>) get.get("parameters"), "filter[{field}][{op}]")
                .get("description");

        for (FilterOperator operator : FilterOperator.values()) {
            String token = operator.name().toLowerCase(Locale.ROOT);
            assertThat(description).as("operator %s is documented", token).contains(token);
            assertThat(FilterOperator.parse(token)).isEqualTo(operator);
        }
    }

    @Test
    @DisplayName("Field validation failures are documented as 400, not 422")
    @SuppressWarnings("unchecked")
    void validationFailuresAreDocumentedAs400() {
        Map<String, Object> spec = generator.generate(
                List.of(createCollection("contacts", "Contacts", false, false)), null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>)
                paths.get("/api/contacts")).get("post");
        Map<String, Object> postResponses = (Map<String, Object>) post.get("responses");

        assertThat(description(postResponses, "400")).contains("Validation error");
        // 422 stays, but only for what actually returns it: custom formula rules.
        assertThat(description(postResponses, "422")).contains("custom validation rule");

        Map<String, Object> patch = (Map<String, Object>) ((Map<String, Object>)
                paths.get("/api/contacts/{id}")).get("patch");
        Map<String, Object> patchResponses = (Map<String, Object>) patch.get("responses");
        assertThat(description(patchResponses, "400")).contains("Validation error");
        assertThat(patchResponses).containsKey("404");
    }

    @Test
    @DisplayName("Response schemas carry enum, default, description and required")
    @SuppressWarnings("unchecked")
    void responseSchemasCarryFieldMetadata() {
        Map<String, Object> spec = generator.generate(
                List.of(SystemCollectionDefinitions.byName().get("page-layouts")), null);

        Map<String, Object> schemas = (Map<String, Object>) components(spec).get("schemas");
        Map<String, Object> resource = (Map<String, Object>) schemas.get("page-layoutsResource");
        Map<String, Object> properties = (Map<String, Object>) resource.get("properties");

        Map<String, Object> attributes = (Map<String, Object>) ((Map<String, Object>)
                properties.get("attributes")).get("properties");
        Map<String, Object> layoutType = (Map<String, Object>) attributes.get("layoutType");
        assertThat((List<String>) layoutType.get("enum"))
                .containsExactly("DETAIL", "EDIT", "MINI", "LIST");
        assertThat(layoutType.get("default")).isEqualTo("DETAIL");
        assertThat((String) layoutType.get("description")).isNotBlank();

        // Relationship fields belong under relationships, not attributes.
        assertThat(attributes).doesNotContainKey("collectionId");
        Map<String, Object> relationships = (Map<String, Object>) ((Map<String, Object>)
                properties.get("relationships")).get("properties");
        assertThat(relationships).containsKey("collectionId");

        Map<String, Object> request = (Map<String, Object>) schemas.get("page-layoutsRequest");
        Map<String, Object> requestAttributes = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) request.get("properties")).get("data"))
                        .get("properties")).get("attributes");
        assertThat((List<String>) requestAttributes.get("required")).contains("name");
    }

    @Test
    @DisplayName("Read-only collections document only GET, including system ones")
    @SuppressWarnings("unchecked")
    void readOnlySystemCollectionsDocumentOnlyGet() {
        Map<String, Object> spec = generator.generate(
                List.of(SystemCollectionDefinitions.byName().get("flow-executions")), null);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> list = (Map<String, Object>) paths.get("/api/flow-executions");
        assertThat(list).containsKey("get");
        assertThat(list).doesNotContainKey("post");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> components(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("components");
    }

    private static Map<String, Object> paramNamed(List<Map<String, Object>> params, String name) {
        return params.stream()
                .filter(p -> name.equals(p.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + name + "' parameter documented"));
    }

    @SuppressWarnings("unchecked")
    private static String description(Map<String, Object> responses, String status) {
        Map<String, Object> response = (Map<String, Object>) responses.get(status);
        assertThat(response).as("response %s is documented", status).isNotNull();
        return (String) response.get("description");
    }
}
