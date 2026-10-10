package io.kelta.worker.listener;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("OwnerScopeGuardHook")
class OwnerScopeGuardHookTest {

    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final String COLLECTION = "watchlists";

    private static final CallerContext MEMBER = new CallerContext(ME, UserType.PORTAL, false, false);
    private static final CallerContext STAFF = new CallerContext(ME, UserType.INTERNAL, false, false);
    private static final CallerContext STAFF_MODIFY_ALL = new CallerContext(ME, UserType.INTERNAL, false, true);

    private CollectionRegistry registry;
    private QueryEngine queryEngine;
    private OwnerScopeGuardHook hook;

    @BeforeEach
    void setUp() {
        registry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        hook = new OwnerScopeGuardHook(registry, queryEngine);
        when(registry.get(COLLECTION)).thenReturn(watchlists(OwnerScope.PORTAL));
    }

    private static CollectionDefinition watchlists(OwnerScope scope) {
        return CollectionDefinition.builder()
                .name(COLLECTION)
                .addField(FieldDefinition.lookup("member", "users", "Member"))
                .addField(FieldDefinition.string("label"))
                .ownerField("member")
                .ownerScope(scope)
                .build();
    }

    private static Map<String, Object> record(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static void assertNotFound(Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("create stamps the owner field with the caller")
    void createStampsOwner() {
        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeCreate(COLLECTION, record("label", "mine"), "t1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFieldUpdates()).containsEntry("member", ME);
    }

    @Test
    @DisplayName("create with the caller's own id as owner is admitted")
    void createWithOwnIdIsAdmitted() {
        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeCreate(COLLECTION, record("member", ME), "t1"));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("create with a different client-supplied owner → 400 OWNER_MISMATCH")
    void createForSomeoneElseIsRejected() {
        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeCreate(COLLECTION, record("member", OTHER), "t1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("member");
            assertThat(e.code()).isEqualTo(OwnerScopeGuardHook.OWNER_MISMATCH);
        });
    }

    @Test
    @DisplayName("update of the caller's own row is admitted")
    void ownUpdateIsAdmitted() {
        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeUpdate(COLLECTION, "r1", record("label", "x"), record("member", ME), "t1"));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("update of another member's row → 404")
    void foreignUpdateIsNotFound() {
        assertNotFound(() -> CallerContext.runAs(MEMBER,
                () -> hook.beforeUpdate(COLLECTION, "r1", record("label", "x"), record("member", OTHER), "t1")));
    }

    @Test
    @DisplayName("update of an unowned row (no stored owner) → 404")
    void unownedUpdateIsNotFound() {
        assertNotFound(() -> CallerContext.runAs(MEMBER,
                () -> hook.beforeUpdate(COLLECTION, "r1", record("label", "x"), record("label", "y"), "t1")));
    }

    @Test
    @DisplayName("the owner field is immutable on update → 400 OWNER_IMMUTABLE")
    void ownerFieldIsImmutable() {
        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeUpdate(COLLECTION, "r1", record("member", OTHER), record("member", ME), "t1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).singleElement()
                .satisfies(e -> assertThat(e.code()).isEqualTo(OwnerScopeGuardHook.OWNER_IMMUTABLE));
    }

    @Test
    @DisplayName("delete of the caller's own row is admitted")
    void ownDeleteIsAdmitted() {
        when(queryEngine.getById(any(), anyString())).thenReturn(Optional.of(record("id", "r1", "member", ME)));

        BeforeSaveResult result = CallerContext.callAs(MEMBER, () -> hook.beforeDelete(COLLECTION, "r1", "t1"));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("delete of another member's row → 404")
    void foreignDeleteIsNotFound() {
        when(queryEngine.getById(any(), anyString())).thenReturn(Optional.of(record("id", "r1", "member", OTHER)));

        assertNotFound(() -> CallerContext.runAs(MEMBER, () -> hook.beforeDelete(COLLECTION, "r1", "t1")));
    }

    @Test
    @DisplayName("delete of a row the caller cannot read (or that does not exist) → 404")
    void missingDeleteIsNotFound() {
        when(queryEngine.getById(any(), anyString())).thenReturn(Optional.empty());

        assertNotFound(() -> CallerContext.runAs(MEMBER, () -> hook.beforeDelete(COLLECTION, "r1", "t1")));
    }

    @Test
    @DisplayName("internal tier (no CallerContext) is admitted for create, update and delete")
    void internalTierIsAdmitted() {
        assertThat(hook.beforeCreate(COLLECTION, record("member", OTHER), "t1").isSuccess()).isTrue();
        assertThat(hook.beforeUpdate(COLLECTION, "r1", record("member", ME), record("member", OTHER), "t1")
                .isSuccess()).isTrue();
        assertThat(hook.beforeDelete(COLLECTION, "r1", "t1").isSuccess()).isTrue();
        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("INTERNAL staff under PORTAL scope are not owner-scoped")
    void staffUnderPortalScopeIsAdmitted() {
        BeforeSaveResult result = CallerContext.callAs(STAFF,
                () -> hook.beforeUpdate(COLLECTION, "r1", record("member", OTHER), record("member", OTHER), "t1"));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("ALL scope: staff without MODIFY_ALL_DATA are scoped; with it they bypass")
    void allScopeHonoursModifyAll() {
        when(registry.get(COLLECTION)).thenReturn(watchlists(OwnerScope.ALL));

        assertNotFound(() -> CallerContext.runAs(STAFF,
                () -> hook.beforeUpdate(COLLECTION, "r1", record("label", "x"), record("member", OTHER), "t1")));
        assertThat(CallerContext.callAs(STAFF_MODIFY_ALL,
                () -> hook.beforeUpdate(COLLECTION, "r1", record("label", "x"), record("member", OTHER), "t1"))
                .isSuccess()).isTrue();
    }

    @Test
    @DisplayName("collections without ownership are untouched")
    void unownedCollectionsAreIgnored() {
        when(registry.get("contacts")).thenReturn(CollectionDefinition.builder()
                .name("contacts").addField(FieldDefinition.string("name")).build());

        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeCreate("contacts", record("name", "x"), "t1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFieldUpdates()).isFalse();
    }

    // ------------------------------------------------------------------
    // The seven collections whose bespoke owner-guard hooks this guard replaced (member data
    // ownership slice 4): four system collections declared in SystemCollectionDefinitions, and
    // three tenant collections owned by createdBy with unscoped reads, set through metadata.
    // ------------------------------------------------------------------

    static Stream<Named<CollectionDefinition>> retiredHookShapes() {
        return Stream.of(
                SystemCollectionDefinitions.watches(),
                SystemCollectionDefinitions.wins(),
                SystemCollectionDefinitions.userUiPreferences(),
                SystemCollectionDefinitions.notes(),
                tenantCreatedByCollection("field-reports"),
                tenantCreatedByCollection("facility-photos"),
                tenantCreatedByCollection("facility-comments"))
                .map(definition -> Named.of(definition.name(), definition));
    }

    private static CollectionDefinition tenantCreatedByCollection(String name) {
        return CollectionDefinition.builder()
                .name(name)
                .addField(FieldDefinition.lookup("createdBy", "users", "Created By"))
                .addField(FieldDefinition.string("body"))
                .ownerField("createdBy")
                .ownerScope(OwnerScope.ALL)
                .ownerScopeReads(false)
                .build();
    }

    private void register(CollectionDefinition definition) {
        when(registry.get(definition.name())).thenReturn(definition);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredHookShapes")
    @DisplayName("create on behalf of another member is rejected")
    void shapeCreateForAnotherMemberIsRejected(CollectionDefinition definition) {
        register(definition);
        String owner = definition.ownerField();

        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeCreate(definition.name(), record(owner, OTHER), "t1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo(owner);
            assertThat(e.code()).isEqualTo(OwnerScopeGuardHook.OWNER_MISMATCH);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredHookShapes")
    @DisplayName("create for yourself is admitted and stamped with the caller")
    void shapeOwnCreateIsStamped(CollectionDefinition definition) {
        register(definition);

        BeforeSaveResult result = CallerContext.callAs(MEMBER,
                () -> hook.beforeCreate(definition.name(), record(), "t1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFieldUpdates()).containsEntry(definition.ownerField(), ME);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredHookShapes")
    @DisplayName("update of another member's record → 404; staff without MODIFY_ALL_DATA too")
    void shapeForeignUpdateIsNotFound(CollectionDefinition definition) {
        register(definition);
        Map<String, Object> previous = record(definition.ownerField(), OTHER);

        assertNotFound(() -> CallerContext.runAs(MEMBER,
                () -> hook.beforeUpdate(definition.name(), "r1", record("x", "y"), previous, "t1")));
        assertNotFound(() -> CallerContext.runAs(STAFF,
                () -> hook.beforeUpdate(definition.name(), "r1", record("x", "y"), previous, "t1")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredHookShapes")
    @DisplayName("delete of another member's record → 404")
    void shapeForeignDeleteIsNotFound(CollectionDefinition definition) {
        register(definition);
        when(queryEngine.getById(any(), anyString()))
                .thenReturn(Optional.of(record("id", "r1", definition.ownerField(), OTHER)));

        assertNotFound(() -> CallerContext.runAs(MEMBER,
                () -> hook.beforeDelete(definition.name(), "r1", "t1")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredHookShapes")
    @DisplayName("internal tier (no CallerContext) is admitted for create, update and delete")
    void shapeInternalTierIsAdmitted(CollectionDefinition definition) {
        register(definition);
        String owner = definition.ownerField();

        assertThat(hook.beforeCreate(definition.name(), record(owner, OTHER), "t1").isSuccess()).isTrue();
        assertThat(hook.beforeUpdate(definition.name(), "r1", record(owner, ME), record(owner, OTHER), "t1")
                .isSuccess()).isTrue();
        assertThat(hook.beforeDelete(definition.name(), "r1", "t1").isSuccess()).isTrue();
        verifyNoInteractions(queryEngine);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredHookShapes")
    @DisplayName("MODIFY_ALL_DATA bypasses the guard for create, update and delete")
    void shapeModifyAllBypassIsAdmitted(CollectionDefinition definition) {
        register(definition);
        String owner = definition.ownerField();

        CallerContext.runAs(STAFF_MODIFY_ALL, () -> {
            assertThat(hook.beforeCreate(definition.name(), record(owner, OTHER), "t1").isSuccess()).isTrue();
            assertThat(hook.beforeUpdate(definition.name(), "r1", record("x", "y"), record(owner, OTHER), "t1")
                    .isSuccess()).isTrue();
            assertThat(hook.beforeDelete(definition.name(), "r1", "t1").isSuccess()).isTrue();
        });
        verifyNoInteractions(queryEngine);
    }
}
