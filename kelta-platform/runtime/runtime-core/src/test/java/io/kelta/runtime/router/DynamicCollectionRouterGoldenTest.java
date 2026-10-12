package io.kelta.runtime.router;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidQueryException;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.SortDirection;
import io.kelta.runtime.query.SortField;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.ReferencedRecordConflictException;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Characterization ("golden") tests for {@link DynamicCollectionRouter}.
 *
 * <p>Every public handler is driven through MockMvc (with {@link GlobalExceptionHandler} as the
 * controller advice, exactly as the worker wires it) against an in-memory recording
 * {@link QueryEngine}, {@link SystemCollectionCache} and {@link UserIdResolver}. Each case
 * records, into {@code src/test/resources/router-golden/<case>.json}:
 * <ul>
 *   <li>the response status, the {@code ETag} / {@code Location} / {@code Content-Type}
 *       headers and the exact JSON body — every value and every array's order. Object keys are
 *       compared sorted: parts of the document are built with {@code Map.of(...)}, whose
 *       iteration order is randomized per JVM, so key order is not a property the router
 *       guarantees (and no JSON client may rely on it);</li>
 *   <li>the ordered log of every collaborator call the request made — query-engine reads and
 *       writes (with the exact data map handed to storage and the {@code TenantContext} bound at
 *       the time), cache lookups/puts/evictions and user-id resolutions — so a refactor that
 *       reorders side effects, drops a stamp or changes what reaches storage fails here even when
 *       the response body is unchanged.</li>
 * </ul>
 *
 * <p>The only normalization is the random {@code requestId} that {@link GlobalExceptionHandler}
 * puts in error {@code meta}; every id and timestamp is deterministic by construction.
 *
 * <p>These pin current behaviour, not intent — including divergences listed in
 * {@code concerns.md} → {@code DynamicCollectionRouter} internals. Changing one is a behaviour
 * change and must be a deliberate, reviewed golden update. Regenerate with
 * {@code -Drouter.golden.update=true} and review the diff.
 */
class DynamicCollectionRouterGoldenTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/router-golden");
    private static final boolean UPDATE = Boolean.getBoolean("router.golden.update");

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String SYS = SystemCollectionDefinitions.SYSTEM_TENANT_ID;
    private static final String USER_EMAIL = "alice@example.com";
    private static final String USER_UUID = "11111111-1111-1111-1111-111111111111";

    private final ObjectMapper mapper = new ObjectMapper();

    private List<Map<String, Object>> calls;
    private FakeQueryEngine engine;
    private RecordingCache cache;
    private Map<String, CollectionDefinition> definitions;
    private MockMvc mockMvc;

    // ==================== Fixtures ====================

    private static CollectionDefinition accounts() {
        return new CollectionDefinitionBuilder()
                .name("accounts").displayName("Accounts")
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.requiredString("slug").withUnique(true))
                .addField(new FieldDefinition("amount", FieldType.CURRENCY, true, false, false,
                        null, null, null, null, null))
                .addField(FieldDefinition.lookup("region", "regions", "Region"))
                .addField(FieldDefinition.string("secret").withServerOnly(true))
                .displayFieldName("slug")
                .captureGeo(true)
                .build();
    }

    private static CollectionDefinition contacts() {
        return new CollectionDefinitionBuilder()
                .name("contacts").displayName("Contacts")
                .addField(FieldDefinition.masterDetail("account", "accounts", "Account"))
                .addField(FieldDefinition.string("firstName"))
                .addField(FieldDefinition.lookup("employer", "companies", "Employer"))
                .build();
    }

    private static CollectionDefinition notes() {
        return new CollectionDefinitionBuilder()
                .name("notes").displayName("Notes")
                .addField(FieldDefinition.lookup("contact", "contacts", "Contact"))
                .addField(FieldDefinition.string("body"))
                .build();
    }

    private static CollectionDefinition simple(String name, String field) {
        return new CollectionDefinitionBuilder()
                .name(name).displayName(name)
                .addField(FieldDefinition.string(field))
                .build();
    }

    private static CollectionDefinition uiMenus() {
        return new CollectionDefinitionBuilder()
                .name("ui-menus").displayName("UI Menus")
                .addField(FieldDefinition.requiredString("name"))
                .systemCollection(true).tenantScoped(true).readOnly(false)
                .build();
    }

    private static CollectionDefinition auditLogs() {
        return new CollectionDefinitionBuilder()
                .name("audit-logs").displayName("Audit Logs")
                .addField(FieldDefinition.string("action"))
                .addField(FieldDefinition.reference("accountId", "accounts"))
                .systemCollection(true).tenantScoped(false).readOnly(true)
                .build();
    }

    private static CollectionDefinition collectionsDef() {
        return new CollectionDefinitionBuilder()
                .name("collections").displayName("Collections")
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.bool("systemCollection"))
                .systemCollection(true).tenantScoped(true).readOnly(false)
                .build();
    }

    private static CollectionDefinition fieldsDef() {
        return new CollectionDefinitionBuilder()
                .name("fields").displayName("Fields")
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.reference("collectionId", "collections"))
                .systemCollection(true).tenantScoped(true).readOnly(false)
                .build();
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        engine = new FakeQueryEngine(calls);
        cache = new RecordingCache(calls);

        definitions = new HashMap<>();
        for (CollectionDefinition def : List.of(accounts(), contacts(), notes(),
                simple("regions", "name"), simple("companies", "name"), simple("users", "email"),
                uiMenus(), auditLogs(), collectionsDef(), fieldsDef())) {
            definitions.put(def.name(), def);
        }
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(anyString())).thenAnswer(inv -> definitions.get(inv.<String>getArgument(0)));

        String t0 = "2026-01-01T00:00:00Z";
        engine.seed("accounts",
                row("id", "acc-1", "name", "Acme", "slug", "acme", "amount", 100.5,
                        "amount_currency_code", "USD", "region", "reg-1", "secret", "s3cr3t",
                        "legacy_col", "orphan", "createdAt", t0, "updatedAt", t0,
                        "createdBy", "user-1", "updatedBy", "user-2"),
                row("id", "acc-2", "name", "Beta", "slug", "beta", "amount", null, "region", null,
                        "createdAt", t0, "updatedAt", "2026-01-03T00:00:00Z", "createdBy", "user-1",
                        "updatedBy", "user-1"),
                row("id", "acc-3", "name", "Gamma", "slug", "gamma", "region", "reg-2",
                        "createdAt", t0, "updatedAt", t0, "createdBy", "user-2", "updatedBy", "user-2"));
        engine.seed("contacts",
                row("id", "con-1", "account", "acc-1", "firstName", "Ann", "employer", "co-1"),
                row("id", "con-2", "account", "acc-1", "firstName", "Bob", "employer", null),
                row("id", "con-3", "account", "acc-2", "firstName", "Cid", "employer", "co-2"));
        engine.seed("notes",
                row("id", "note-1", "contact", "con-1", "body", "hi"),
                row("id", "note-2", "contact", "con-3", "body", "yo"));
        engine.seed("regions", row("id", "reg-1", "name", "North"), row("id", "reg-2", "name", "South"));
        engine.seed("companies", row("id", "co-1", "name", "Initech"), row("id", "co-2", "name", "Globex"));
        engine.seed("users", row("id", "user-1", "email", "a@x.test"), row("id", "user-2", "email", "b@x.test"));
        engine.seed("ui-menus",
                row("id", "menu-1", "tenantId", TENANT_A, "name", "Main", "updatedAt", t0),
                row("id", "menu-2", "tenantId", TENANT_A, "name", "Admin", "updatedAt", t0),
                row("id", "menu-b", "tenantId", TENANT_B, "name", "Other", "updatedAt", t0));
        engine.seed("audit-logs", row("id", "log-1", "action", "login", "accountId", "acc-1"));
        engine.seed("collections",
                row("id", "col-sys", "tenantId", SYS, "name", "users", "systemCollection", true),
                row("id", "col-plat", "tenantId", SYS, "name", "platform-custom", "systemCollection", false),
                row("id", "col-a", "tenantId", TENANT_A, "name", "accounts", "systemCollection", false),
                row("id", "col-b", "tenantId", TENANT_B, "name", "orders", "systemCollection", false));
        engine.seed("fields",
                row("id", "f-sys", "tenantId", SYS, "name", "email", "collectionId", "col-sys"),
                row("id", "f-plat", "tenantId", SYS, "name", "notes", "collectionId", "col-plat"),
                row("id", "f-a", "tenantId", TENANT_A, "name", "name", "collectionId", "col-a"),
                row("id", "f-b", "tenantId", TENANT_B, "name", "total", "collectionId", "col-b"));

        DynamicCollectionRouter router = new DynamicCollectionRouter(registry, engine);
        router.setSystemCollectionCache(cache);
        router.setUserIdResolver((identifier, tenantId) -> {
            calls.add(entry("op", "userIdResolver.resolve", "identifier", identifier, "tenant", tenantId));
            return USER_EMAIL.equals(identifier) ? USER_UUID : null;
        });
        mockMvc = MockMvcBuilders.standaloneSetup(router)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ==================== Request helpers ====================

    private static MockHttpServletRequestBuilder tenantA(MockHttpServletRequestBuilder b) {
        return b.header("X-Tenant-ID", TENANT_A).header("X-Tenant-Slug", "acme-co")
                .header("X-User-Id", USER_EMAIL);
    }

    private static MockHttpServletRequestBuilder geo(MockHttpServletRequestBuilder b) {
        return b.header("X-Geo-Country", "PT").header("X-Geo-City", "Lisbon")
                .header("X-Geo-Lat", "38.72").header("X-Geo-Lon", "-9.14");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String etagOf(String uri) throws Exception {
        return mockMvc.perform(tenantA(get(uri))).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    // ==================== List ====================

    @Nested
    @DisplayName("GET /api/{collection}")
    class ListGoldens {
        @Test void defaultList() throws Exception { golden("list_default", tenantA(get("/api/accounts"))); }

        @Test void paging() throws Exception {
            golden("list_paging", tenantA(get("/api/accounts").param("page[number]", "2").param("page[size]", "2")));
        }

        @Test void pageSizeClamped() throws Exception {
            golden("list_page_size_clamped", tenantA(get("/api/accounts").param("page[size]", "500")));
        }

        @Test void sort() throws Exception {
            golden("list_sort_desc", tenantA(get("/api/accounts").param("sort", "-name")));
        }

        @Test void filter() throws Exception {
            golden("list_filter_eq", tenantA(get("/api/accounts").param("filter[name][eq]", "Acme")));
        }

        @Test void filterOnUnknownField() throws Exception {
            golden("list_filter_unknown_field", tenantA(get("/api/accounts").param("filter[nope][eq]", "x")));
        }

        @Test void sparseFieldset() throws Exception {
            golden("list_sparse_fields", tenantA(get("/api/accounts").param("fields[accounts]", "name,slug")));
        }

        @Test void includeHasMany() throws Exception {
            golden("list_include_has_many", tenantA(get("/api/accounts").param("include", "contacts")));
        }

        @Test void includeNestedHasMany() throws Exception {
            golden("list_include_nested_has_many",
                    tenantA(get("/api/accounts").param("include", "contacts,notes")));
        }

        @Test void includeTransitiveBelongsTo() throws Exception {
            golden("list_include_transitive_belongs_to",
                    tenantA(get("/api/accounts").param("include", "contacts,companies")));
        }

        @Test void includeBelongsTo() throws Exception {
            golden("list_include_belongs_to", tenantA(get("/api/accounts").param("include", "regions")));
        }

        @Test void includeFieldName() throws Exception {
            golden("list_include_field_name", tenantA(get("/api/accounts").param("include", "region")));
        }

        @Test void includeUsersViaAuditFields() throws Exception {
            golden("list_include_users_audit", tenantA(get("/api/accounts").param("include", "users")));
        }

        @Test void includeUnknown() throws Exception {
            golden("list_include_unknown", tenantA(get("/api/accounts").param("include", "bogus,contacts")));
        }

        @Test void includeOnEmptyResult() throws Exception {
            golden("list_include_empty_result", tenantA(get("/api/accounts")
                    .param("filter[name][eq]", "Nobody").param("include", "contacts")));
        }

        @Test void unknownCollection() throws Exception {
            golden("list_unknown_collection", tenantA(get("/api/nope")));
        }

        @Test void systemCollectionTenantFilterAndCache() throws Exception {
            golden("list_system_first_miss", tenantA(get("/api/ui-menus")));
            golden("list_system_second_hit", tenantA(get("/api/ui-menus")));
        }

        @Test void systemCollectionWithoutTenantHeader() throws Exception {
            golden("list_system_no_tenant_header", get("/api/ui-menus"));
        }

        @Test void sharedSystemRowsNarrowed() throws Exception {
            golden("list_shared_system_rows", tenantA(get("/api/collections")));
        }

        @Test void sharedSystemRowsWithInclude() throws Exception {
            golden("list_shared_system_rows_include_fields",
                    tenantA(get("/api/collections").param("include", "fields")));
        }

        @Test void readOnlySystemCollectionNotCached() throws Exception {
            golden("list_read_only_system", tenantA(get("/api/audit-logs")));
        }
    }

    // ==================== Get ====================

    @Nested
    @DisplayName("GET /api/{collection}/{id}")
    class GetGoldens {
        @Test void found() throws Exception { golden("get_found", tenantA(get("/api/accounts/acc-1"))); }

        @Test void notFoundNonUuidFallsBackToDisplayField() throws Exception {
            golden("get_not_found_display_lookup", tenantA(get("/api/accounts/acc-999")));
        }

        @Test void notFoundUuid() throws Exception {
            golden("get_not_found_uuid",
                    tenantA(get("/api/accounts/00000000-0000-0000-0000-000000000999")));
        }

        @Test void byDisplayField() throws Exception {
            golden("get_by_display_field", tenantA(get("/api/accounts/beta")));
        }

        @Test void unknownCollection() throws Exception {
            golden("get_unknown_collection", tenantA(get("/api/nope/x")));
        }

        @Test void includeHasMany() throws Exception {
            golden("get_include_has_many", tenantA(get("/api/accounts/acc-1").param("include", "contacts")));
        }

        @Test void includeFieldName() throws Exception {
            golden("get_include_field_name", tenantA(get("/api/accounts/acc-1").param("include", "region,regions")));
        }

        @Test void systemOwnTenantThenCacheHit() throws Exception {
            golden("get_system_first_miss", tenantA(get("/api/ui-menus/menu-1")));
            golden("get_system_second_hit", tenantA(get("/api/ui-menus/menu-1")));
        }

        @Test void systemWithIncludeSkipsCache() throws Exception {
            golden("get_system_include_skips_cache",
                    tenantA(get("/api/collections/col-a").param("include", "fields")));
        }

        @Test void systemOtherTenantIsNotFound() throws Exception {
            golden("get_system_other_tenant", tenantA(get("/api/ui-menus/menu-b")));
        }

        @Test void sharedSystemRow() throws Exception {
            golden("get_shared_system_row", tenantA(get("/api/collections/col-sys")));
        }
    }

    // ==================== Create ====================

    @Nested
    @DisplayName("POST /api/{collection}")
    class CreateGoldens {
        @Test void valid() throws Exception {
            golden("create_valid", geo(json(tenantA(post("/api/accounts")), """
                    {"data":{"type":"accounts","attributes":{"name":"Delta","slug":"delta","secret":"pw",
                     "amount":5,"amount_currency_code":"EUR"},
                     "relationships":{"region":{"data":{"type":"regions","id":"reg-2"}},
                                      "owner":{"data":{"type":"users","id":"user-1"}}}}}
                    """)));
        }

        @Test void plainBody() throws Exception {
            golden("create_plain_body", json(tenantA(post("/api/accounts")), """
                    {"name":"Plain","slug":"plain"}
                    """));
        }

        @Test void nullRelationship() throws Exception {
            golden("create_null_relationship", json(tenantA(post("/api/accounts")), """
                    {"data":{"type":"accounts","attributes":{"name":"Eps","slug":"eps"},
                     "relationships":{"region":{"data":null},"ignored":{"meta":{}}}}}
                    """));
        }

        @Test void uuidUserIdIsNotResolved() throws Exception {
            golden("create_uuid_user_id", json(post("/api/accounts")
                    .header("X-Tenant-ID", TENANT_A)
                    .header("X-User-Id", "22222222-2222-2222-2222-222222222222"), """
                    {"name":"Zed","slug":"zed"}
                    """));
        }

        @Test void validationError() throws Exception {
            golden("create_validation_error", json(tenantA(post("/api/accounts")), """
                    {"data":{"type":"accounts","attributes":{"name":"INVALID","slug":"bad"}}}
                    """));
        }

        @Test void duplicate() throws Exception {
            golden("create_duplicate", json(tenantA(post("/api/accounts")), """
                    {"data":{"type":"accounts","attributes":{"name":"DUP","slug":"acme"}}}
                    """));
        }

        @Test void systemCollectionInjectsTenantAndEvicts() throws Exception {
            golden("create_system_collection", geo(json(tenantA(post("/api/ui-menus")), """
                    {"data":{"type":"ui-menus","attributes":{"name":"New"}}}
                    """)));
        }

        @Test void systemCollectionKeepsExplicitTenant() throws Exception {
            golden("create_system_explicit_tenant", json(tenantA(post("/api/ui-menus")), """
                    {"data":{"type":"ui-menus","attributes":{"name":"New","tenantId":"tenant-x"}}}
                    """));
        }

        @Test void readOnly() throws Exception {
            golden("create_read_only", json(tenantA(post("/api/audit-logs")), """
                    {"data":{"type":"audit-logs","attributes":{"action":"x"}}}
                    """));
        }

        @Test void unknownCollection() throws Exception {
            golden("create_unknown_collection", json(tenantA(post("/api/nope")), "{}"));
        }

        @Test void malformedJson() throws Exception {
            golden("create_malformed_json", json(tenantA(post("/api/accounts")), "{\"data\":"));
        }

        @Test void unsupportedMediaType() throws Exception {
            golden("create_unsupported_media_type", tenantA(post("/api/accounts"))
                    .contentType(MediaType.TEXT_PLAIN).content("name=x"));
        }
    }

    // ==================== Update ====================

    @Nested
    @DisplayName("PUT/PATCH /api/{collection}/{id}")
    class UpdateGoldens {
        @Test void putValid() throws Exception {
            golden("put_valid", geo(json(tenantA(put("/api/accounts/acc-1")), """
                    {"data":{"type":"accounts","id":"acc-1","attributes":{"name":"Acme 2"}}}
                    """)));
        }

        @Test void patchPartial() throws Exception {
            golden("patch_partial", json(tenantA(patch("/api/accounts/acc-2")), """
                    {"data":{"type":"accounts","id":"acc-2","attributes":{"slug":"beta-2"}}}
                    """));
        }

        @Test void patchRelationships() throws Exception {
            golden("patch_relationships", json(tenantA(patch("/api/accounts/acc-1")), """
                    {"data":{"type":"accounts","id":"acc-1","relationships":{
                      "region":{"data":{"type":"regions","id":"reg-2"}},
                      "owner":{"data":{"type":"users","id":"user-2"}}}}}
                    """));
        }

        @Test void patchIfMatchMatches() throws Exception {
            String etag = etagOf("/api/accounts/acc-1");
            calls.clear();
            golden("patch_if_match_match", json(tenantA(patch("/api/accounts/acc-1")).header(HttpHeaders.IF_MATCH, etag), """
                    {"data":{"attributes":{"name":"Locked"}}}
                    """));
        }

        @Test void patchIfMatchMismatch() throws Exception {
            golden("patch_if_match_mismatch", json(tenantA(patch("/api/accounts/acc-1"))
                    .header(HttpHeaders.IF_MATCH, "\"stale\""), """
                    {"data":{"attributes":{"name":"Locked"}}}
                    """));
        }

        @Test void patchIfMatchWildcard() throws Exception {
            golden("patch_if_match_wildcard", json(tenantA(patch("/api/accounts/acc-1"))
                    .header(HttpHeaders.IF_MATCH, "*"), """
                    {"data":{"attributes":{"name":"Any"}}}
                    """));
        }

        @Test void patchIfMatchOnMissingRecord() throws Exception {
            golden("patch_if_match_missing_record", json(tenantA(patch("/api/accounts/acc-404"))
                    .header(HttpHeaders.IF_MATCH, "\"stale\""), """
                    {"data":{"attributes":{"name":"Ghost"}}}
                    """));
        }

        @Test void patchNotFound() throws Exception {
            golden("patch_not_found", json(tenantA(patch("/api/accounts/acc-404")), """
                    {"data":{"attributes":{"name":"Ghost"}}}
                    """));
        }

        @Test void patchValidationError() throws Exception {
            golden("patch_validation_error", json(tenantA(patch("/api/accounts/acc-1")), """
                    {"data":{"attributes":{"name":"INVALID"}}}
                    """));
        }

        @Test void putReadOnly() throws Exception {
            golden("put_read_only", json(tenantA(put("/api/audit-logs/log-1")), """
                    {"data":{"attributes":{"action":"x"}}}
                    """));
        }

        @Test void patchUnknownCollection() throws Exception {
            golden("patch_unknown_collection", json(tenantA(patch("/api/nope/x")), "{}"));
        }

        @Test void patchSystemCollectionEvicts() throws Exception {
            golden("patch_system_collection", json(tenantA(patch("/api/ui-menus/menu-1")), """
                    {"data":{"attributes":{"name":"Renamed"}}}
                    """));
        }
    }

    // ==================== Delete ====================

    @Nested
    @DisplayName("DELETE /api/{collection}/{id}")
    class DeleteGoldens {
        @Test void found() throws Exception { golden("delete_found", tenantA(delete("/api/accounts/acc-2"))); }

        @Test void notFound() throws Exception { golden("delete_not_found", tenantA(delete("/api/accounts/acc-404"))); }

        @Test void referenced() throws Exception {
            engine.referencedIds.add("acc-1");
            golden("delete_referenced", tenantA(delete("/api/accounts/acc-1")));
        }

        @Test void ifMatchMismatch() throws Exception {
            golden("delete_if_match_mismatch", tenantA(delete("/api/accounts/acc-1"))
                    .header(HttpHeaders.IF_MATCH, "\"stale\""));
        }

        @Test void ifMatchMatches() throws Exception {
            String etag = etagOf("/api/accounts/acc-3");
            calls.clear();
            golden("delete_if_match_match", tenantA(delete("/api/accounts/acc-3")).header(HttpHeaders.IF_MATCH, etag));
        }

        @Test void readOnly() throws Exception { golden("delete_read_only", tenantA(delete("/api/audit-logs/log-1"))); }

        @Test void unknownCollection() throws Exception { golden("delete_unknown_collection", tenantA(delete("/api/nope/x"))); }

        @Test void systemCollectionEvicts() throws Exception {
            golden("delete_system_collection", tenantA(delete("/api/ui-menus/menu-2")));
        }
    }

    // ==================== Sub-resources ====================

    @Nested
    @DisplayName("/api/{parent}/{parentId}/{child}[/{childId}]")
    class ChildGoldens {
        @Test void list() throws Exception {
            golden("child_list", tenantA(get("/api/accounts/acc-1/contacts")));
        }

        @Test void listWithFilterAndPaging() throws Exception {
            golden("child_list_filter_paging", tenantA(get("/api/accounts/acc-1/contacts")
                    .param("filter[firstName][eq]", "Ann").param("page[size]", "1")));
        }

        @Test void listIgnoresInclude() throws Exception {
            golden("child_list_include_ignored", tenantA(get("/api/accounts/acc-1/contacts").param("include", "notes")));
        }

        @Test void listNoRelationship() throws Exception {
            golden("child_list_no_relationship", tenantA(get("/api/accounts/acc-1/regions")));
        }

        @Test void listUnknownParent() throws Exception {
            golden("child_list_unknown_parent", tenantA(get("/api/nope/acc-1/contacts")));
        }

        @Test void listUnknownChild() throws Exception {
            golden("child_list_unknown_child", tenantA(get("/api/accounts/acc-1/nope")));
        }

        @Test void getChild() throws Exception {
            golden("child_get", tenantA(get("/api/accounts/acc-1/contacts/con-1")));
        }

        @Test void getNotFound() throws Exception {
            golden("child_get_not_found", tenantA(get("/api/accounts/acc-1/contacts/con-404")));
        }

        @Test void getNoRelationship() throws Exception {
            golden("child_get_no_relationship", tenantA(get("/api/accounts/acc-1/regions/reg-1")));
        }

        @Test void create() throws Exception {
            golden("child_create", geo(json(tenantA(post("/api/accounts/acc-1/contacts")), """
                    {"data":{"type":"contacts","attributes":{"firstName":"Dee","account":"acc-9"},
                     "relationships":{"employer":{"data":{"type":"companies","id":"co-2"}},
                                      "extra":{"data":{"type":"x","id":"x-1"}}}}}
                    """)));
        }

        @Test void createValidationError() throws Exception {
            golden("child_create_validation_error", json(tenantA(post("/api/accounts/acc-1/contacts")), """
                    {"data":{"attributes":{"firstName":"INVALID"}}}
                    """));
        }

        @Test void createReadOnly() throws Exception {
            golden("child_create_read_only", json(tenantA(post("/api/accounts/acc-1/audit-logs")), """
                    {"data":{"attributes":{"action":"x"}}}
                    """));
        }

        @Test void createNoRelationship() throws Exception {
            golden("child_create_no_relationship", json(tenantA(post("/api/accounts/acc-1/regions")), "{}"));
        }

        @Test void putChild() throws Exception {
            golden("child_put", geo(json(tenantA(put("/api/accounts/acc-1/contacts/con-1")), """
                    {"data":{"attributes":{"firstName":"Annie"},
                     "relationships":{"employer":{"data":null}}}}
                    """)));
        }

        @Test void patchIgnoresIfMatch() throws Exception {
            golden("child_patch_if_match_ignored", json(tenantA(patch("/api/accounts/acc-1/contacts/con-2"))
                    .header(HttpHeaders.IF_MATCH, "\"stale\""), """
                    {"data":{"attributes":{"firstName":"Bobby"}}}
                    """));
        }

        @Test void patchNotFound() throws Exception {
            golden("child_patch_not_found", json(tenantA(patch("/api/accounts/acc-1/contacts/con-404")), """
                    {"data":{"attributes":{"firstName":"Ghost"}}}
                    """));
        }

        @Test void putReadOnly() throws Exception {
            golden("child_put_read_only", json(tenantA(put("/api/accounts/acc-1/audit-logs/log-1")), """
                    {"data":{"attributes":{"action":"x"}}}
                    """));
        }

        @Test void putNoRelationship() throws Exception {
            golden("child_put_no_relationship", json(tenantA(put("/api/accounts/acc-1/regions/reg-1")), "{}"));
        }

        @Test void deleteChild() throws Exception {
            golden("child_delete", tenantA(delete("/api/accounts/acc-1/contacts/con-2")));
        }

        @Test void deleteIgnoresIfMatch() throws Exception {
            golden("child_delete_if_match_ignored", tenantA(delete("/api/accounts/acc-1/contacts/con-1"))
                    .header(HttpHeaders.IF_MATCH, "\"stale\""));
        }

        @Test void deleteNotFound() throws Exception {
            golden("child_delete_not_found", tenantA(delete("/api/accounts/acc-1/contacts/con-404")));
        }

        @Test void deleteReferenced() throws Exception {
            engine.referencedIds.add("con-1");
            golden("child_delete_referenced", tenantA(delete("/api/accounts/acc-1/contacts/con-1")));
        }

        @Test void deleteReadOnly() throws Exception {
            golden("child_delete_read_only", tenantA(delete("/api/accounts/acc-1/audit-logs/log-1")));
        }

        @Test void deleteNoRelationship() throws Exception {
            golden("child_delete_no_relationship", tenantA(delete("/api/accounts/acc-1/regions/reg-1")));
        }
    }

    // ==================== Routing / bad input ====================

    @Nested
    @DisplayName("Routing and bad input")
    class RoutingGoldens {
        @Test void postOnRecordPathIsMethodNotAllowed() throws Exception {
            golden("routing_post_on_record_path", json(tenantA(post("/api/accounts/acc-1")), "{}"));
        }

        @Test void fiveSegmentPathHasNoHandler() throws Exception {
            golden("routing_five_segments", tenantA(get("/api/a/b/c/d/e")));
        }

        @Test void postOnFourSegmentPathIsMethodNotAllowed() throws Exception {
            golden("routing_post_on_child_record_path", json(tenantA(post("/api/accounts/acc-1/contacts/con-1")), "{}"));
        }
    }

    // ==================== Golden machinery ====================

    private void golden(String name, MockHttpServletRequestBuilder request) throws Exception {
        int callStart = calls.size();
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();

        ObjectNode doc = mapper.createObjectNode();
        doc.put("status", response.getStatus());
        ObjectNode headers = doc.putObject("headers");
        for (String header : List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION, HttpHeaders.CONTENT_TYPE)) {
            String value = response.getHeader(header);
            if (value != null) {
                headers.put(header, value);
            }
        }
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        if (content.isEmpty()) {
            doc.putNull("body");
        } else {
            // Object keys are sorted: the router and GlobalExceptionHandler build parts of the
            // document with Map.of(...), whose iteration order is randomized per JVM, so key order
            // is not a stable property of the response. Array order is, and is kept.
            JsonNode body = mapper.valueToTree(canonical(mapper.readValue(content, Object.class)));
            normalize(body);
            doc.set("body", body);
        }
        ArrayNode callNodes = doc.putArray("calls");
        for (Map<String, Object> call : calls.subList(callStart, calls.size())) {
            callNodes.add(mapper.valueToTree(canonical(call)));
        }

        String actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n";
        Path file = GOLDEN_DIR.resolve(name + ".json");
        if (UPDATE) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(file)) {
            fail("Missing golden " + file + " — run with -Drouter.golden.update=true and review it.\n" + actual);
        }
        String expected = Files.readString(file, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            // Leave the actual output next to the build for a plain `diff -r`.
            Path actualDir = Path.of("target/router-golden-actual");
            Files.createDirectories(actualDir);
            Files.writeString(actualDir.resolve(name + ".json"), actual, StandardCharsets.UTF_8);
        }
        assertEquals(expected, actual, "golden mismatch: " + name);
    }

    /** Replaces the random per-error {@code requestId} that GlobalExceptionHandler generates. */
    private static void normalize(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.properties().forEach(e -> names.add(e.getKey()));
            for (String name : names) {
                if ("requestId".equals(name)) {
                    obj.put(name, "<requestId>");
                } else {
                    normalize(obj.get(name));
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                normalize(child);
            }
        }
    }

    /** Key-sorted deep copy so HashMap iteration order in recorded call args never matters. */
    @SuppressWarnings("unchecked")
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(canonical(v)));
            return out;
        }
        return value;
    }

    private static Map<String, Object> entry(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================== Fakes ====================

    /**
     * In-memory {@link QueryEngine} that applies EQ/IN filters, single-key sorting, paging and
     * sparse fieldsets, and logs every call with the bound {@link TenantContext}. Like the real
     * engine it rejects filters on unknown fields; unlike it, it returns server-only fields, so
     * the router's own server-only/orphan-column guard is what the goldens see.
     */
    static final class FakeQueryEngine implements QueryEngine {
        private static final Set<String> META = Set.of(
                "id", "tenantId", "createdBy", "updatedBy", "createdAt", "updatedAt");

        private final List<Map<String, Object>> calls;
        private final Map<String, LinkedHashMap<String, Map<String, Object>>> tables = new HashMap<>();
        final Set<String> referencedIds = new HashSet<>();
        private int seq;

        FakeQueryEngine(List<Map<String, Object>> calls) {
            this.calls = calls;
        }

        @SafeVarargs
        final void seed(String collection, Map<String, Object>... rows) {
            LinkedHashMap<String, Map<String, Object>> table =
                    tables.computeIfAbsent(collection, k -> new LinkedHashMap<>());
            for (Map<String, Object> r : rows) {
                table.put((String) r.get("id"), r);
            }
        }

        private LinkedHashMap<String, Map<String, Object>> table(CollectionDefinition def) {
            return tables.computeIfAbsent(def.name(), k -> new LinkedHashMap<>());
        }

        @Override
        public QueryResult executeQuery(CollectionDefinition definition, QueryRequest request) {
            calls.add(entry("op", "executeQuery", "collection", definition.name(),
                    "tenant", TenantContext.get(), "query", request.toString()));
            for (FilterCondition f : request.filters()) {
                if (definition.getField(f.fieldName()) == null && !META.contains(f.fieldName())) {
                    throw new InvalidQueryException(f.fieldName(), "Unknown field '" + f.fieldName() + "'");
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : table(definition).values()) {
                if (request.filters().stream().allMatch(f -> matches(r, f))) {
                    rows.add(r);
                }
            }
            if (!request.sorting().isEmpty()) {
                SortField s = request.sorting().get(0);
                Comparator<Map<String, Object>> cmp = Comparator.comparing(
                        r -> String.valueOf(r.get(s.fieldName())));
                rows.sort(s.direction() == SortDirection.DESC ? cmp.reversed() : cmp);
            }
            int size = request.pagination().pageSize();
            int page = request.pagination().pageNumber();
            int from = Math.min(rows.size(), (page - 1) * size);
            int to = Math.min(rows.size(), from + size);
            List<Map<String, Object>> pageRows = new ArrayList<>();
            for (Map<String, Object> r : rows.subList(from, to)) {
                Map<String, Object> copy = new HashMap<>(r);
                if (!request.fields().isEmpty()) {
                    copy.keySet().removeIf(k -> !"id".equals(k) && !request.fields().contains(k));
                }
                pageRows.add(copy);
            }
            int totalPages = (int) Math.ceil((double) rows.size() / size);
            return new QueryResult(pageRows, new PaginationMetadata(rows.size(), page, size, totalPages));
        }

        private static boolean matches(Map<String, Object> r, FilterCondition f) {
            String actual = String.valueOf(r.get(f.fieldName()));
            if (f.operator() == FilterOperator.EQ) {
                return actual.equals(String.valueOf(f.value()));
            }
            if (f.operator() == FilterOperator.IN && f.value() instanceof java.util.Collection<?> c) {
                return c.stream().anyMatch(v -> actual.equals(String.valueOf(v)));
            }
            return true;
        }

        @Override
        public Map<String, Object> aggregate(CollectionDefinition definition, List<FilterCondition> filters,
                                             List<AggregationSpec> specs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Map<String, Object>> getById(CollectionDefinition definition, String id) {
            calls.add(entry("op", "getById", "collection", definition.name(),
                    "tenant", TenantContext.get(), "id", id));
            Map<String, Object> r = table(definition).get(id);
            return r == null ? Optional.empty() : Optional.of(new HashMap<>(r));
        }

        @Override
        public Map<String, Object> create(CollectionDefinition definition, Map<String, Object> data) {
            calls.add(entry("op", "create", "collection", definition.name(),
                    "tenant", TenantContext.get(), "data", new HashMap<>(data)));
            reject(definition, data);
            Map<String, Object> r = new HashMap<>(data);
            String id = definition.name() + "-new-" + (++seq);
            r.put("id", id);
            r.put("createdAt", "2026-02-01T00:00:00Z");
            r.put("updatedAt", "2026-02-01T00:00:00Z");
            table(definition).put(id, r);
            return new HashMap<>(r);
        }

        @Override
        public Optional<Map<String, Object>> update(CollectionDefinition definition, String id,
                                                    Map<String, Object> data) {
            calls.add(entry("op", "update", "collection", definition.name(),
                    "tenant", TenantContext.get(), "id", id, "data", new HashMap<>(data)));
            reject(definition, data);
            Map<String, Object> r = table(definition).get(id);
            if (r == null) {
                return Optional.empty();
            }
            r.putAll(data);
            r.put("updatedAt", "2026-02-02T00:00:00Z");
            return Optional.of(new HashMap<>(r));
        }

        @Override
        public boolean delete(CollectionDefinition definition, String id) {
            calls.add(entry("op", "delete", "collection", definition.name(),
                    "tenant", TenantContext.get(), "id", id));
            if (referencedIds.contains(id)) {
                throw new ReferencedRecordConflictException(definition.name(), id, null);
            }
            return table(definition).remove(id) != null;
        }

        private static void reject(CollectionDefinition definition, Map<String, Object> data) {
            Object name = data.containsKey("name") ? data.get("name") : data.get("firstName");
            if ("INVALID".equals(name)) {
                String field = data.containsKey("name") ? "name" : "firstName";
                throw new ValidationException(ValidationResult.failure(
                        new FieldError(field, "Value is not allowed", "pattern")));
            }
            if ("DUP".equals(name)) {
                throw new UniqueConstraintViolationException(definition.name(), "slug", data.get("slug"));
            }
        }
    }

    /** {@link SystemCollectionCache} that really caches (so hits are observable) and logs. */
    static final class RecordingCache implements SystemCollectionCache {
        private final List<Map<String, Object>> calls;
        private final Map<String, Map<String, Object>> store = new HashMap<>();

        RecordingCache(List<Map<String, Object>> calls) {
            this.calls = calls;
        }

        @Override
        public Optional<Map<String, Object>> getListResponse(String tenantId, String collectionName, String queryHash) {
            Map<String, Object> hit = store.get("list|" + tenantId + "|" + collectionName + "|" + queryHash);
            calls.add(entry("op", "cache.getListResponse", "tenant", tenantId, "collection", collectionName,
                    "queryHash", queryHash, "hit", hit != null));
            return Optional.ofNullable(hit);
        }

        @Override
        public void putListResponse(String tenantId, String collectionName, String queryHash,
                                    Map<String, Object> response) {
            calls.add(entry("op", "cache.putListResponse", "tenant", tenantId, "collection", collectionName,
                    "queryHash", queryHash));
            store.put("list|" + tenantId + "|" + collectionName + "|" + queryHash, response);
        }

        @Override
        public Optional<Map<String, Object>> getByIdResponse(String tenantId, String collectionName, String recordId) {
            Map<String, Object> hit = store.get("id|" + tenantId + "|" + collectionName + "|" + recordId);
            calls.add(entry("op", "cache.getByIdResponse", "tenant", tenantId, "collection", collectionName,
                    "id", recordId, "hit", hit != null));
            return Optional.ofNullable(hit);
        }

        @Override
        public void putByIdResponse(String tenantId, String collectionName, String recordId,
                                    Map<String, Object> response) {
            calls.add(entry("op", "cache.putByIdResponse", "tenant", tenantId, "collection", collectionName,
                    "id", recordId));
            store.put("id|" + tenantId + "|" + collectionName + "|" + recordId, response);
        }

        @Override
        public void evict(String tenantId, String collectionName) {
            calls.add(entry("op", "cache.evict", "tenant", tenantId, "collection", collectionName));
            store.keySet().removeIf(k -> k.contains("|" + tenantId + "|" + collectionName + "|"));
        }

        @Override
        public void evictAll() {
            calls.add(entry("op", "cache.evictAll"));
            store.clear();
        }
    }

    @Test
    @DisplayName("every golden file on disk is produced by a test (no stale goldens)")
    void noOrphanGoldens() throws Exception {
        // Guarded by the count rather than a listing so a deleted test leaves a visible failure.
        if (UPDATE || !Files.isDirectory(GOLDEN_DIR)) {
            return;
        }
        try (var files = Files.list(GOLDEN_DIR)) {
            long count = files.filter(p -> p.toString().endsWith(".json")).count();
            assertEquals(EXPECTED_GOLDEN_COUNT, count,
                    "golden file count changed — add/remove the matching test, or update EXPECTED_GOLDEN_COUNT");
        }
    }

    private static final long EXPECTED_GOLDEN_COUNT = 93;

}
