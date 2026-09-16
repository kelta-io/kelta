package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.DashboardTreeService.ApplyResult;
import io.kelta.worker.service.DashboardTreeService.TreeValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idempotent dashboard tree: one call upserts the dashboard row plus its widgets, matched
 * on title. Covers convergence (a re-applied body writes nothing), widget create/update/delete,
 * and that a brand-new dashboard skips the components pre-read (there is nothing to diff yet).
 */
@DisplayName("DashboardTreeService")
class DashboardTreeServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String NAME = "Library Overview";

    private QueryEngine queryEngine;
    private JdbcTemplate jdbcTemplate;
    private DashboardTreeService service;

    private final List<Map<String, Object>> componentRows = new ArrayList<>();

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
        when(jdbcTemplate.queryForList(contains("FROM dashboard WHERE name"), eq(NAME)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM dashboard_component WHERE dashboard_id"),
                anyString())).thenReturn(componentRows);

        service = new DashboardTreeService(queryEngine, collectionRegistry, jdbcTemplate, new ObjectMapper());
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }

    private static Map<String, Object> component(String title) {
        return row("title", title, "componentType", "metric", "columnPosition", 1, "rowPosition", 1,
                "config", Map.of("collectionName", "books"));
    }

    private ApplyResult apply(Map<String, Object> body) {
        return TenantContext.callWithTenant(TENANT, () -> service.applyTree(NAME, body));
    }

    @Test
    @DisplayName("First apply creates the dashboard and every widget")
    void firstApplyCreates() {
        Map<String, Object> body = row("columnCount", 3,
                "components", List.of(component("Total Books"), component("Overdue Loans")));

        ApplyResult result = apply(body);

        assertThat(result.counts().created()).isEqualTo(3); // dashboard + two widgets
        assertThat(result.counts().updated()).isZero();
        assertThat(result.counts().deleted()).isZero();
        assertThat(result.name()).isEqualTo(NAME);
        // Brand-new dashboard: no pre-read of dashboard-components needed.
        verify(jdbcTemplate, never()).queryForList(contains("FROM dashboard_component"), anyString());
    }

    @Test
    @DisplayName("Re-applying the same body writes nothing")
    void secondApplyIsANoOp() {
        seedExistingDashboard();
        componentRows.add(componentRow("wc-1", "Total Books", 1, 1));
        componentRows.add(componentRow("wc-2", "Overdue Loans", 1, 1));

        Map<String, Object> body = row("columnCount", 3,
                "components", List.of(component("Total Books"), component("Overdue Loans")));
        ApplyResult result = apply(body);

        assertThat(result.counts()).isEqualTo(new DashboardTreeService.TreeCounts(0, 0, 0, 3));
        verify(queryEngine, never()).create(any(), any());
        verify(queryEngine, never()).update(any(), anyString(), any());
    }

    @Test
    @DisplayName("A widget dropped from the body is deleted")
    void droppedWidgetIsDeleted() {
        seedExistingDashboard();
        componentRows.add(componentRow("wc-1", "Total Books", 1, 1));
        componentRows.add(componentRow("wc-2", "Overdue Loans", 2, 1));

        ApplyResult result = apply(row("components", List.of(component("Total Books"))));

        assertThat(result.counts().deleted()).isEqualTo(1);
        verify(queryEngine).delete(any(), eq("wc-2"));
    }

    @Test
    @DisplayName("A duplicate widget title is a 400 pointing at the second occurrence")
    void duplicateTitleRejected() {
        Map<String, Object> body = row("components", List.of(component("Total Books"), component("Total Books")));

        assertThatThrownBy(() -> apply(body))
                .isInstanceOf(TreeValidationException.class)
                .satisfies(e -> assertThat(((TreeValidationException) e).errors())
                        .singleElement()
                        .satisfies(error -> assertThat(error.pointer()).isEqualTo("/components/1/title")));
    }

    @Test
    @DisplayName("A missing components array is a 400 — nothing is written")
    void componentsAreRequired() {
        assertThatThrownBy(() -> apply(row("name", NAME)))
                .isInstanceOf(TreeValidationException.class);
        verify(queryEngine, never()).create(any(), any());
    }

    private void seedExistingDashboard() {
        when(jdbcTemplate.queryForList(contains("FROM dashboard WHERE name"), eq(NAME)))
                .thenReturn(List.of(row("id", "dash-1", "name", NAME, "description", null,
                        "access_level", "PRIVATE", "is_dynamic", false, "running_user_id", null,
                        "column_count", 3)));
    }

    private static Map<String, Object> componentRow(String id, String title, int col, int rowPos) {
        return row("id", id, "title", title, "component_type", "metric",
                "column_position", col, "row_position", rowPos, "column_span", 1, "row_span", 1,
                "config", "{\"collectionName\":\"books\"}");
    }
}
