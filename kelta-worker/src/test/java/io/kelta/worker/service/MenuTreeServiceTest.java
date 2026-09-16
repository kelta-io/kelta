package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.MenuTreeService.ApplyResult;
import io.kelta.worker.service.MenuTreeService.TreeValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idempotent menu tree: one call upserts the menu row plus its items/groups, matched on
 * (parent, label). Covers convergence, group + child creation with the child's parentId
 * resolved to the freshly-created group's id, and deletion of a dropped item.
 */
@DisplayName("MenuTreeService")
class MenuTreeServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String NAME = "Main";

    private QueryEngine queryEngine;
    private JdbcTemplate jdbcTemplate;
    private MenuTreeService service;

    private final List<Map<String, Object>> itemRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        CollectionRegistry collectionRegistry = mock(CollectionRegistry.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        when(collectionRegistry.get(anyString())).thenAnswer(inv ->
                SystemCollectionDefinitions.byName().get(inv.<String>getArgument(0)));
        AtomicInteger sequence = new AtomicInteger();
        when(queryEngine.create(any(), any())).thenAnswer(inv ->
                Map.of("id", "new-" + sequence.incrementAndGet()));

        when(jdbcTemplate.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM ui_menu WHERE name"), eq(NAME)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM ui_menu_item WHERE menu_id"), anyString()))
                .thenReturn(itemRows);

        service = new MenuTreeService(queryEngine, collectionRegistry, jdbcTemplate);
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }

    private ApplyResult apply(Map<String, Object> body) {
        return TenantContext.callWithTenant(TENANT, () -> service.applyTree(NAME, body));
    }

    @Test
    @DisplayName("First apply creates the menu, a group and its child")
    void firstApplyCreatesGroupAndChild() {
        Map<String, Object> body = row("items", List.of(
                row("label", "Catalog", "children", List.of(row("label", "Books", "path", "/resources/books")))));

        ApplyResult result = apply(body);

        assertThat(result.counts().created()).isEqualTo(3); // menu + group + child
        assertThat(result.name()).isEqualTo(NAME);
        verify(jdbcTemplate, never()).queryForList(contains("FROM ui_menu_item"), anyString());
    }

    @Test
    @DisplayName("Re-applying the same body writes nothing")
    void secondApplyIsANoOp() {
        seedExistingMenu();
        itemRows.add(itemRow("grp-1", null, "Catalog", null, 0));
        itemRows.add(itemRow("child-1", "grp-1", "Books", "/resources/books", 0));

        Map<String, Object> body = row("items", List.of(
                row("label", "Catalog", "children", List.of(row("label", "Books", "path", "/resources/books")))));
        ApplyResult result = apply(body);

        assertThat(result.counts()).isEqualTo(new MenuTreeService.TreeCounts(0, 0, 0, 3));
        verify(queryEngine, never()).create(any(), any());
        verify(queryEngine, never()).update(any(), anyString(), any());
    }

    @Test
    @DisplayName("An item dropped from the body is deleted")
    void droppedItemIsDeleted() {
        seedExistingMenu();
        itemRows.add(itemRow("top-1", null, "Reports", "/reports/sales", 0));
        itemRows.add(itemRow("top-2", null, "Tasks", "/resources/tasks", 1));

        ApplyResult result = apply(row("items", List.of(row("label", "Tasks", "path", "/resources/tasks"))));

        assertThat(result.counts().deleted()).isEqualTo(1);
        verify(queryEngine).delete(any(), eq("top-1"));
    }

    @Test
    @DisplayName("Nesting deeper than one level is a 400")
    void deepNestingRejected() {
        Map<String, Object> body = row("items", List.of(row("label", "A", "children", List.of(
                row("label", "B", "children", List.of(row("label", "C")))))));

        assertThatThrownBy(() -> apply(body)).isInstanceOf(TreeValidationException.class);
    }

    @Test
    @DisplayName("A missing items array is a 400 — nothing is written")
    void itemsAreRequired() {
        assertThatThrownBy(() -> apply(row("name", NAME))).isInstanceOf(TreeValidationException.class);
        verify(queryEngine, never()).create(any(), any());
    }

    private void seedExistingMenu() {
        when(jdbcTemplate.queryForList(contains("FROM ui_menu WHERE name"), eq(NAME)))
                .thenReturn(List.of(row("id", "menu-1", "name", NAME, "description", null,
                        "icon", null, "is_default", false, "active", true, "display_order", 0)));
    }

    private static Map<String, Object> itemRow(String id, String parentId, String label, String path, int order) {
        return row("id", id, "parent_id", parentId, "label", label, "path", path, "icon", null,
                "display_order", order);
    }
}
