package io.kelta.runtime.storage;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Member data ownership slice 2: reads of an owner-scoped collection carry an
 * {@code <owner column> = <caller>} predicate in SQL — query, its count, aggregate and get-by-id —
 * but only for a caller the collection's {@link OwnerScope} actually limits.
 */
@DisplayName("PhysicalTableStorageAdapter — owner scoping")
class PhysicalTableStorageAdapterOwnerScopeTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String MEMBER = "22222222-2222-2222-2222-222222222222";

    private static final CallerContext PORTAL_MEMBER = new CallerContext(MEMBER, UserType.PORTAL, false, false);
    private static final CallerContext STAFF = new CallerContext(MEMBER, UserType.INTERNAL, false, false);
    private static final CallerContext STAFF_VIEW_ALL = new CallerContext(MEMBER, UserType.INTERNAL, true, false);
    private static final CallerContext STAFF_MODIFY_ALL = new CallerContext(MEMBER, UserType.INTERNAL, false, true);

    private JdbcTemplate jdbcTemplate;
    private PhysicalTableStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        SchemaMigrationEngine migrationEngine = mock(SchemaMigrationEngine.class);
        adapter = new PhysicalTableStorageAdapter(
                jdbcTemplate, migrationEngine, new tools.jackson.databind.ObjectMapper());
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("total", 0L));
        when(migrationEngine.getExistingColumns(anyString(), anyString())).thenReturn(Set.of("id", "member"));
    }

    private static CollectionDefinition watchlists(OwnerScope scope, boolean reads) {
        return new CollectionDefinitionBuilder()
                .name("watchlists")
                .addField(FieldDefinition.lookup("member", "users", "Member"))
                .addField(FieldDefinition.string("label"))
                .ownerField("member")
                .ownerScope(scope)
                .ownerScopeReads(reads)
                .build();
    }

    private static QueryRequest query() {
        return new QueryRequest(Pagination.defaults(), List.of(), List.of(), List.of());
    }

    private <T> T as(CallerContext caller, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TenantContext.callWithTenant(TENANT, () ->
                caller == null ? work.call() : CallerContext.callAs(caller, work));
    }

    private String selectSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    private Object[] selectParams() {
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), params.capture());
        return params.getValue();
    }

    private String countSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        return sql.getValue();
    }

    private String aggregateSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForMap(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    private void aggregate(CallerContext caller, CollectionDefinition def) {
        as(caller, () -> adapter.aggregate(def, List.of(), List.of(new AggregationSpec("COUNT", null, "total"))));
    }

    @Test
    @DisplayName("PORTAL scope, portal caller: query, count, aggregate and getById carry the owner predicate")
    void portalCallerIsScopedEverywhere() {
        CollectionDefinition def = watchlists(OwnerScope.PORTAL, true);

        as(PORTAL_MEMBER, () -> adapter.query(def, query()));
        assertThat(selectSql()).contains("\"member\" = ?");
        assertThat(selectParams()).contains(MEMBER);
        assertThat(countSql()).contains("\"member\" = ?");

        aggregate(PORTAL_MEMBER, def);
        assertThat(aggregateSql()).contains("WHERE \"member\" = ?");
    }

    @Test
    @DisplayName("getById ANDs the owner predicate so a foreign row reads as absent")
    void getByIdIsScoped() {
        CollectionDefinition def = watchlists(OwnerScope.PORTAL, true);

        assertThat(as(PORTAL_MEMBER, () -> adapter.getById(def, "rec-1"))).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("WHERE id = ? AND \"member\" = ?");
        assertThat(params.getValue()).containsExactly("rec-1", MEMBER);
    }

    @Test
    @DisplayName("PORTAL scope, INTERNAL caller: no owner predicate")
    void internalCallerUnderPortalScopeIsUnaffected() {
        CollectionDefinition def = watchlists(OwnerScope.PORTAL, true);

        as(STAFF, () -> adapter.query(def, query()));
        assertThat(selectSql()).doesNotContain("\"member\"");

        aggregate(STAFF, def);
        assertThat(aggregateSql()).doesNotContain("\"member\"");
    }

    @Test
    @DisplayName("internal tier (no CallerContext): no owner predicate")
    void internalTierIsUnscoped() {
        as(null, () -> adapter.getById(watchlists(OwnerScope.ALL, true), "rec-1"));
        assertThat(selectSql()).doesNotContain("\"member\"");
    }

    @Test
    @DisplayName("ALL scope: VIEW_ALL_DATA bypasses reads; MODIFY_ALL_DATA alone does not")
    void allScopeBypassIsReadOnlyViewAll() {
        CollectionDefinition def = watchlists(OwnerScope.ALL, true);

        as(STAFF_VIEW_ALL, () -> adapter.getById(def, "rec-1"));
        as(STAFF_MODIFY_ALL, () -> adapter.getById(def, "rec-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues().get(0)).doesNotContain("\"member\"");
        assertThat(sql.getAllValues().get(1)).contains("\"member\" = ?");
    }

    @Test
    @DisplayName("ALL scope: plain staff and portal callers are both scoped")
    void allScopeScopesStaffWithoutBypass() {
        as(STAFF, () -> adapter.query(watchlists(OwnerScope.ALL, true), query()));
        assertThat(selectSql()).contains("\"member\" = ?");
    }

    @Test
    @DisplayName("ALL scope: a row shared with the caller (user or group) stays readable")
    void allScopeHonoursRecordShares() {
        as(STAFF, () -> adapter.getById(watchlists(OwnerScope.ALL, true), "rec-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue())
                .contains("(\"member\" = ? OR id IN (SELECT rs.record_id FROM record_share rs")
                .contains("group_membership gm");
        assertThat(params.getValue()).containsExactly("rec-1", MEMBER, "watchlists", MEMBER, MEMBER);
    }

    @Test
    @DisplayName("PORTAL scope: shares do not widen a member's reads")
    void portalScopeIgnoresShares() {
        as(PORTAL_MEMBER, () -> adapter.query(watchlists(OwnerScope.PORTAL, true), query()));
        assertThat(selectSql()).doesNotContain("record_share");
    }

    @Test
    @DisplayName("ownerScopeReads=false: no read predicate on query, aggregate or getById")
    void writesOnlyScopeAddsNoReadPredicate() {
        CollectionDefinition def = watchlists(OwnerScope.PORTAL, false);

        as(PORTAL_MEMBER, () -> adapter.query(def, query()));
        assertThat(selectSql()).doesNotContain("\"member\"");
        assertThat(countSql()).doesNotContain("\"member\"");

        aggregate(PORTAL_MEMBER, def);
        assertThat(aggregateSql()).doesNotContain("\"member\"");
    }

    @Test
    @DisplayName("NONE scope: no owner predicate even for a portal caller")
    void noneScopeIsUnscoped() {
        as(PORTAL_MEMBER, () -> adapter.query(watchlists(OwnerScope.NONE, true), query()));
        assertThat(selectSql()).doesNotContain("\"member\"");
    }

    @Test
    @DisplayName("createdBy as owner field maps to the created_by column")
    void createdByOwnerMapsToColumn() {
        CollectionDefinition def = new CollectionDefinitionBuilder()
                .name("notes2")
                .addField(FieldDefinition.string("body"))
                .ownerField("createdBy")
                .ownerScope(OwnerScope.PORTAL)
                .build();

        as(PORTAL_MEMBER, () -> adapter.query(def, query()));
        assertThat(selectSql()).contains("\"created_by\" = ?");
    }
}
