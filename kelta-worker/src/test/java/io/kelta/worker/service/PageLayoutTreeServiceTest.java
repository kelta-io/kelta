package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.PageLayoutTreeService.ApplyResult;
import io.kelta.worker.service.PageLayoutTreeService.TreeValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idempotent layout tree: names in, a diff out. Covers the convergence contract (a
 * re-applied body writes nothing), the GET → PUT round trip, deletion of what the body drops,
 * and the pointer-bearing 400s for an unknown field, an out-of-range column, a related list
 * whose relationship field does not point back at the layout's collection, and an echoed
 * {@code layoutId}/{@code collection} naming a layout other than the one addressed.
 */
@DisplayName("PageLayoutTreeService")
class PageLayoutTreeServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String LAYOUT_ID = "layout-1";
    private static final String COLLECTION_ID = "coll-1";
    private static final String COLLECTION = "contacts";

    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private JdbcTemplate jdbcTemplate;
    private PageLayoutTreeService service;

    private final List<Map<String, Object>> sectionRows = new ArrayList<>();
    private final List<Map<String, Object>> placementRows = new ArrayList<>();
    private final List<Map<String, Object>> relatedListRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        when(collectionRegistry.get(anyString())).thenAnswer(inv ->
                SystemCollectionDefinitions.byName().get(inv.<String>getArgument(0)));
        AtomicInteger sequence = new AtomicInteger();
        when(queryEngine.create(any(), any())).thenAnswer(inv ->
                Map.of("id", "new-" + sequence.incrementAndGet()));

        // Every unstubbed lookup is "nothing there" — a fresh layout on a fresh collection.
        when(jdbcTemplate.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class))).thenReturn(List.of());

        when(jdbcTemplate.queryForList(contains("FROM page_layout pl"), eq(LAYOUT_ID), eq(TENANT)))
                .thenReturn(List.of(row("id", LAYOUT_ID, "collection_id", COLLECTION_ID,
                        "name", "Contact Detail", "description", null,
                        "layout_type", "DETAIL", "is_default", true, "header_config", null)));
        when(jdbcTemplate.queryForList(contains("SELECT name FROM collection WHERE id"), eq(COLLECTION_ID), eq(TENANT)))
                .thenReturn(List.of(row("name", COLLECTION)));
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE name"), eq(COLLECTION), eq(TENANT)))
                .thenReturn(List.of(row("id", COLLECTION_ID)));
        when(jdbcTemplate.queryForList(contains("FROM field WHERE collection_id"), eq(COLLECTION_ID)))
                .thenReturn(List.of(
                        row("id", "f1", "name", "firstName"),
                        row("id", "f2", "name", "lastName"),
                        row("id", "f3", "name", "email")));
        when(jdbcTemplate.queryForList(contains("FROM layout_section WHERE layout_id"), eq(LAYOUT_ID)))
                .thenReturn(sectionRows);
        when(jdbcTemplate.queryForList(contains("FROM layout_field lf"), eq(LAYOUT_ID)))
                .thenReturn(placementRows);
        when(jdbcTemplate.queryForList(contains("FROM layout_related_list rl"), eq(LAYOUT_ID)))
                .thenReturn(relatedListRows);

        service = new PageLayoutTreeService(queryEngine, collectionRegistry, jdbcTemplate,
                new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }

    /** The layout as the endpoint itself would have written it: one 2-column section, two fields. */
    private void seedAppliedLayout() {
        sectionRows.add(row("id", "sec-1", "heading", "Overview", "columns", 2,
                "collapsed", false, "sort_order", 0));
        placementRows.add(placement("lf-1", "firstName", "f1", 0, 0));
        placementRows.add(placement("lf-2", "lastName", "f2", 1, 1));
    }

    private static Map<String, Object> placement(String id, String fieldName, String fieldId,
                                                 int column, int sortOrder) {
        return row("id", id, "section_id", "sec-1", "field_id", fieldId, "field_name", fieldName,
                "column_number", column, "sort_order", sortOrder,
                "is_required_on_layout", false, "is_read_only_on_layout", false,
                "label_override", null, "help_text_override", null);
    }

    private static Map<String, Object> field(String name, int column) {
        return row("name", name, "column", column);
    }

    private static Map<String, Object> twoFieldBody() {
        return row("sections", List.of(row(
                "heading", "Overview", "columns", 2, "collapsed", false,
                "fields", List.of(field("firstName", 0), field("lastName", 1)))));
    }

    private ApplyResult apply(Map<String, Object> body) {
        return TenantContext.callWithTenant(TENANT, () -> service.applyTree(LAYOUT_ID, body));
    }

    // ------------------------------------------------------------------
    // Convergence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("First apply creates every row and reports the diff")
    void firstApplyCreates() {
        ApplyResult result = apply(twoFieldBody());

        assertThat(result.counts().created()).isEqualTo(3); // one section + two placements
        assertThat(result.counts().updated()).isZero();
        assertThat(result.counts().deleted()).isZero();
        assertThat(result.counts().unchanged()).isEqualTo(1); // the layout row itself
        assertThat(result.layoutId()).isEqualTo(LAYOUT_ID);
        assertThat(result.collection()).isEqualTo(COLLECTION);
    }

    @Test
    @DisplayName("Re-applying the same body writes nothing — created/updated/deleted are all 0")
    void secondApplyIsANoOp() {
        seedAppliedLayout();

        ApplyResult result = apply(twoFieldBody());

        assertThat(result.counts()).isEqualTo(
                new PageLayoutTreeService.TreeCounts(0, 0, 0, 4));
        verify(queryEngine, never()).create(any(), any());
        verify(queryEngine, never()).update(any(), anyString(), any());
        verify(queryEngine, never()).delete(any(), anyString());
    }

    @Test
    @DisplayName("Placements are written through the QueryEngine so the config hooks fire")
    void writesGoThroughTheQueryEngine() {
        apply(twoFieldBody());

        ArgumentCaptor<CollectionDefinition> definitions =
                ArgumentCaptor.forClass(CollectionDefinition.class);
        ArgumentCaptor<Map<String, Object>> records = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine, times(3)).create(definitions.capture(), records.capture());

        assertThat(definitions.getAllValues()).extracting(CollectionDefinition::name)
                .containsExactly("layout-sections", "layout-fields", "layout-fields");
        // Tenant-scoped system collections only get a tenant_id when the record carries one.
        assertThat(records.getAllValues()).allSatisfy(record ->
                assertThat(record).containsEntry("tenantId", TENANT));
        assertThat(records.getAllValues().get(1))
                .containsEntry("fieldId", "f1")
                .containsEntry("columnNumber", 0)
                .containsEntry("sortOrder", 0);
        assertThat(records.getAllValues().get(2)).containsEntry("columnNumber", 1);
    }

    @Test
    @DisplayName("A field dropped from the body has its placement deleted")
    void droppedFieldIsDeleted() {
        seedAppliedLayout();

        ApplyResult result = apply(row("sections", List.of(row(
                "heading", "Overview", "columns", 2,
                "fields", List.of(field("firstName", 0))))));

        assertThat(result.counts().deleted()).isEqualTo(1);
        assertThat(result.counts().created()).isZero();
        verify(queryEngine).delete(argThat(def -> "layout-fields".equals(def.name())), eq("lf-2"));
    }

    @Test
    @DisplayName("A section dropped from the body is deleted after its placements are")
    void droppedSectionIsDeleted() {
        seedAppliedLayout();

        ApplyResult result = apply(row("sections", List.of()));

        assertThat(result.counts().deleted()).isEqualTo(3); // two placements + the section
        verify(queryEngine).delete(argThat(def -> "layout-sections".equals(def.name())), eq("sec-1"));
    }

    @Test
    @DisplayName("A changed column is an update, not a create/delete pair")
    void changedColumnIsAnUpdate() {
        seedAppliedLayout();

        ApplyResult result = apply(row("sections", List.of(row(
                "heading", "Overview", "columns", 2,
                "fields", List.of(field("firstName", 1), field("lastName", 0))))));

        assertThat(result.counts().updated()).isEqualTo(2);
        assertThat(result.counts().created()).isZero();
        assertThat(result.counts().deleted()).isZero();
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET returns names, not ids, and round-trips through PUT with no diff")
    void readTreeRoundTrips() {
        seedAppliedLayout();
        relatedListRows.add(row("id", "rl-1", "related_collection_id", "coll-2",
                "relationship_field_id", "f9", "display_columns", "[\"firstName\"]",
                "sort_field", "firstName", "sort_direction", "ASC", "row_limit", 10,
                "sort_order", 0, "related_collection_name", "notes",
                "relationship_field_name", "contact"));
        stubRelatedCollection();

        Map<String, Object> tree = TenantContext.callWithTenant(TENANT, () -> service.readTree(LAYOUT_ID));

        assertThat(tree).containsEntry("collection", COLLECTION).containsEntry("name", "Contact Detail");
        assertThat(sections(tree).get(0)).containsEntry("heading", "Overview");
        assertThat(fields(tree, 0)).extracting(f -> f.get("name"))
                .containsExactly("firstName", "lastName");
        assertThat(fields(tree, 0)).allSatisfy(f -> assertThat(f).doesNotContainKey("fieldId"));
        assertThat(relatedLists(tree).get(0))
                .containsEntry("collection", "notes")
                .containsEntry("relationshipField", "contact")
                .containsEntry("displayColumns", List.of("firstName"));

        ApplyResult result = apply(tree);

        assertThat(result.counts()).isEqualTo(
                new PageLayoutTreeService.TreeCounts(0, 0, 0, 5));
    }

    /** `notes.contact` is a lookup back to the layout's collection — a valid related list. */
    private void stubRelatedCollection() {
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE name"), eq("notes"), eq(TENANT)))
                .thenReturn(List.of(row("id", "coll-2")));
        when(jdbcTemplate.queryForList(contains("FROM field WHERE collection_id"), eq("coll-2")))
                .thenReturn(List.of(
                        row("id", "f9", "name", "contact", "reference_collection_id", COLLECTION_ID),
                        row("id", "f10", "name", "firstName", "reference_collection_id", null)));
    }

    // ------------------------------------------------------------------
    // Validation — every rejection carries a JSON Pointer into the body
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An unknown field name is a 400 pointing at that placement's name")
    void unknownFieldName() {
        Map<String, Object> body = row("sections", List.of(row(
                "heading", "Overview", "columns", 2,
                "fields", List.of(field("firstName", 0), field("lastName", 1),
                        field("nope", 0)))));

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> {
                            assertThat(error.pointer()).isEqualTo("/sections/0/fields/2/name");
                            assertThat(error.detail()).contains("nope").contains(COLLECTION);
                        }));
        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    @DisplayName("A column at or past the section's column count is a 400")
    void columnOutsideSection() {
        Map<String, Object> body = row("sections", List.of(row(
                "heading", "Overview", "columns", 2,
                "fields", List.of(field("firstName", 2)))));

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error ->
                                assertThat(error.pointer()).isEqualTo("/sections/0/fields/0/column")));
    }

    @Test
    @DisplayName("The same field placed twice in one layout is a 400")
    void duplicateFieldPlacement() {
        Map<String, Object> body = row("sections", List.of(
                row("heading", "Overview", "columns", 2, "fields", List.of(field("firstName", 0))),
                row("heading", "More", "columns", 1, "fields", List.of(field("firstName", 0)))));

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error ->
                                assertThat(error.pointer()).isEqualTo("/sections/1/fields/0/name")));
    }

    @Test
    @DisplayName("A related list whose relationship field does not point at the layout's collection is a 400")
    void relatedListFieldMustPointBack() {
        stubRelatedCollection();
        Map<String, Object> body = row(
                "sections", List.of(),
                "relatedLists", List.of(row("collection", "notes",
                        "relationshipField", "firstName",
                        "displayColumns", List.of("firstName"))));

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> {
                            assertThat(error.pointer()).isEqualTo("/relatedLists/0/relationshipField");
                            assertThat(error.detail()).contains("not a lookup");
                        }));
    }

    @Test
    @DisplayName("An unknown body property is rejected rather than silently ignored")
    void unknownBodyProperty() {
        Map<String, Object> body = row("sections", List.of(), "sektions", List.of());

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> assertThat(error.pointer()).isEqualTo("/sektions")));
    }

    @Test
    @DisplayName("A missing sections array is a 400 — the tree is never half-applied")
    void sectionsAreRequired() {
        assertThatThrownBy(() -> apply(row("name", "Contact Detail")))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> assertThat(error.pointer()).isEqualTo("/sections")));
    }

    // ------------------------------------------------------------------
    // Create by name
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT by name creates the layout when the collection has none with that name")
    void createsLayoutByName() {
        ApplyResult result = TenantContext.callWithTenant(TENANT, () ->
                service.applyTreeByName(COLLECTION, "Contact Detail", twoFieldBody()));

        assertThat(result.name()).isEqualTo("Contact Detail");
        assertThat(result.counts().created()).isEqualTo(4); // layout + section + two placements
        assertThat(result.counts().unchanged()).isZero();

        ArgumentCaptor<Map<String, Object>> records = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).create(argThat(def -> "page-layouts".equals(def.name())), records.capture());
        assertThat(records.getValue())
                .containsEntry("collectionId", COLLECTION_ID)
                .containsEntry("name", "Contact Detail")
                .containsEntry("layoutType", "DETAIL");
    }

    @Test
    @DisplayName("A body name that contradicts the path is a 400 rather than a silent rename")
    void bodyNameMustMatchPath() {
        Map<String, Object> body = twoFieldBody();
        body.put("name", "Something Else");

        assertThatThrownBy(() -> TenantContext.callWithTenant(TENANT, () ->
                service.applyTreeByName(COLLECTION, "Contact Detail", body)))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> assertThat(error.pointer()).isEqualTo("/name")));
    }

    @Test
    @DisplayName("A GET body pasted onto another collection's layout is a 400, not a silent rewrite")
    void echoedCollectionMustMatchTheAddressedLayout() {
        Map<String, Object> body = twoFieldBody();
        body.put("collection", "accounts");

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> assertThat(error.pointer()).isEqualTo("/collection")));
    }

    @Test
    @DisplayName("A GET body pasted onto another layout of the same collection is a 400")
    void echoedLayoutIdMustMatchTheAddressedLayout() {
        Map<String, Object> body = twoFieldBody();
        body.put("layoutId", "some-other-layout");

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> assertThat(error.pointer()).isEqualTo("/layoutId")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sections(Map<String, Object> tree) {
        return (List<Map<String, Object>>) tree.get("sections");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> tree, int section) {
        return (List<Map<String, Object>>) sections(tree).get(section).get("fields");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> relatedLists(Map<String, Object> tree) {
        return (List<Map<String, Object>>) tree.get("relatedLists");
    }

    // ---- tenant scoping: another tenant's layout or collection reads as "not found" ----

    @Test
    @DisplayName("a layout id from another tenant is not found, not edited")
    void foreignLayoutIdIsNotFound() {
        assertThatThrownBy(() -> TenantContext.callWithTenant("tenant-2", () -> service.applyTree(LAYOUT_ID, twoFieldBody())))
                .isInstanceOf(PageLayoutTreeService.LayoutNotFoundException.class)
                .hasMessageContaining(LAYOUT_ID);
        assertThatThrownBy(() -> TenantContext.callWithTenant("tenant-2", () -> service.readTree(LAYOUT_ID)))
                .isInstanceOf(PageLayoutTreeService.LayoutNotFoundException.class);
        verify(queryEngine, never()).create(any(), any());
        verify(queryEngine, never()).update(any(), any(), any());
        verify(queryEngine, never()).delete(any(), any());
    }

    @Test
    @DisplayName("a collection name that exists only in another tenant is not found — never resolved across tenants")
    void foreignCollectionNameIsNotFound() {
        assertThatThrownBy(() -> TenantContext.callWithTenant("tenant-2",
                () -> service.applyTreeByName(COLLECTION, "Contact Detail", twoFieldBody())))
                .isInstanceOf(PageLayoutTreeService.LayoutNotFoundException.class)
                .hasMessageContaining(COLLECTION);
        verify(queryEngine, never()).create(any(), any());
        verify(jdbcTemplate, never()).queryForList(contains("FROM collection WHERE name"), eq(COLLECTION), eq(TENANT));
    }

    @Test
    @DisplayName("no tenant context → not found, nothing read or written")
    void noTenantContextIsNotFound() {
        assertThatThrownBy(() -> service.applyTree(LAYOUT_ID, twoFieldBody()))
                .isInstanceOf(PageLayoutTreeService.LayoutNotFoundException.class)
                .hasMessageContaining("tenant");
        verify(queryEngine, never()).create(any(), any());
    }
}

