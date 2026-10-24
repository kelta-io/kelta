package io.kelta.worker.service;

import io.kelta.runtime.model.ApiConfig;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the generated per-tenant document through swagger-parser, the same validator a client
 * generator uses, so a document that parses with messages (bad {@code $ref}, missing
 * {@code responses}, invalid parameter shape) fails the build instead of a tenant's codegen.
 */
@DisplayName("OpenAPI document validates with swagger-parser")
class OpenApiSpecValidationTest {

    private final OpenApiGenerator generator = new OpenApiGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static List<CollectionDefinition> fixture() {
        CollectionDefinition accounts = CollectionDefinition.builder()
                .name("accounts")
                .displayName("Accounts")
                .description("Tenant accounts")
                .addField(FieldDefinition.requiredString("name"))
                .build();
        CollectionDefinition orders = CollectionDefinition.builder()
                .name("orders")
                .displayName("Orders")
                .addField(FieldDefinition.requiredString("number"))
                .addField(new FieldDefinition("status", FieldType.PICKLIST, false, false, false,
                        "OPEN", null, List.of("OPEN", "SHIPPED", "CLOSED"), null, null))
                .addField(FieldDefinition.lookup("account", "accounts", "Account"))
                .addField(FieldDefinition.doubleField("total"))
                .build();
        CollectionDefinition ledger = CollectionDefinition.builder()
                .name("ledger-entries")
                .displayName("Ledger Entries")
                .addField(FieldDefinition.requiredString("memo"))
                .readOnly(true)
                .build();

        var collections = new ArrayList<CollectionDefinition>(List.of(accounts, orders, ledger));
        collections.addAll(SystemCollectionDefinitions.all());
        return collections;
    }

    private SwaggerParseResult parse(Map<String, Object> spec) {
        String json = objectMapper.writeValueAsString(spec);
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        return new OpenAPIV3Parser().readContents(json, null, options);
    }

    @Test
    @DisplayName("The full document parses with no validation messages")
    void documentParsesWithoutMessages() {
        SwaggerParseResult result = parse(generator.generate(fixture(), "https://api.kelta.io", "acme"));

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
    }

    @Test
    @DisplayName("The document also validates with no server URL and no tenant slug")
    void documentWithoutServerParsesWithoutMessages() {
        SwaggerParseResult result = parse(generator.generate(fixture(), null, null));

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
    }

    @Test
    @DisplayName("The tenant slug goes on the server URL, paths stay /api/...")
    void tenantSlugPrefixesTheServerUrl() {
        OpenAPI api = parse(generator.generate(fixture(), "https://api.kelta.io/", "acme")).getOpenAPI();

        assertThat(api.getServers()).hasSize(1);
        assertThat(api.getServers().get(0).getUrl()).isEqualTo("https://api.kelta.io/acme");
        assertThat(api.getPaths().keySet()).allSatisfy(path -> assertThat(path).startsWith("/api/"));
    }

    @Test
    @DisplayName("With no configured host the server is the relative tenant prefix")
    void tenantSlugWithoutServerUrlIsRelative() {
        OpenAPI api = parse(generator.generate(fixture(), "", "acme")).getOpenAPI();

        assertThat(api.getServers().get(0).getUrl()).isEqualTo("/acme");
    }

    @Test
    @DisplayName("JWT and PAT bearer schemes are declared and either one satisfies security")
    void declaresJwtAndPatSchemes() {
        OpenAPI api = parse(generator.generate(fixture(), null, "acme")).getOpenAPI();

        Map<String, SecurityScheme> schemes = api.getComponents().getSecuritySchemes();
        assertThat(schemes).containsKeys("bearerAuth", "patAuth");
        assertThat(schemes.get("bearerAuth").getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(schemes.get("bearerAuth").getBearerFormat()).isEqualTo("JWT");

        SecurityScheme pat = schemes.get("patAuth");
        assertThat(pat.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(pat.getScheme()).isEqualTo("bearer");
        assertThat(pat.getDescription()).contains("klt_");

        // Two requirement objects = OR: either scheme alone authorizes the call.
        List<SecurityRequirement> security = api.getSecurity();
        assertThat(security).hasSize(2);
        assertThat(security).anySatisfy(r -> assertThat(r).containsOnlyKeys("bearerAuth"));
        assertThat(security).anySatisfy(r -> assertThat(r).containsOnlyKeys("patAuth"));
    }

    @Test
    @DisplayName("Aggregate and latest operations mirror the runtime-core controllers")
    void aggregateAndLatestOperations() {
        OpenAPI api = parse(generator.generate(fixture(), null, "acme")).getOpenAPI();

        Operation aggregate = api.getPaths().get("/api/orders/aggregate").getGet();
        assertThat(aggregate).isNotNull();
        Map<String, Parameter> aggParams = byName(aggregate.getParameters());
        assertThat(aggParams).containsOnlyKeys("groupBy", "avg", "filter[{field}][{op}]");
        assertThat(aggParams.get("groupBy").getRequired()).isTrue();
        assertThat(aggParams.get("avg").getRequired()).isFalse();
        assertThat(aggregate.getResponses()).containsKeys("200", "400", "404");
        assertThat(api.getPaths().get("/api/orders/aggregate").getPost()).isNull();

        Operation latest = api.getPaths().get("/api/orders/latest").getGet();
        assertThat(latest).isNotNull();
        Map<String, Parameter> latestParams = byName(latest.getParameters());
        assertThat(latestParams).containsOnlyKeys("fields", "filter[{field}][{op}]");
        assertThat(latestParams.get("fields").getRequired()).isTrue();
        assertThat(latest.getResponses()).containsKeys("200", "400", "404");

        // Read-only collections still list, so they get both reads too.
        assertThat(api.getPaths()).containsKeys("/api/ledger-entries/aggregate", "/api/ledger-entries/latest");
    }

    @Test
    @DisplayName("A collection with list disabled gets no aggregate or latest operation")
    void listDisabledCollectionHasNoAggregateOrLatest() {
        CollectionDefinition hidden = CollectionDefinition.builder()
                .name("hidden")
                .addField(FieldDefinition.requiredString("name"))
                .apiConfig(new ApiConfig(false, true, true, true, true, "/api/hidden"))
                .build();

        SwaggerParseResult result = parse(generator.generate(List.of(hidden), null, "acme"));

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getPaths())
                .doesNotContainKeys("/api/hidden/aggregate", "/api/hidden/latest");
    }

    private static Map<String, Parameter> byName(List<Parameter> parameters) {
        return parameters.stream().collect(Collectors.toMap(Parameter::getName, Function.identity()));
    }
}
