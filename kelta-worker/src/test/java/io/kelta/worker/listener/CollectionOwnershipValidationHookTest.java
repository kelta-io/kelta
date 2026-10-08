package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CollectionOwnershipValidationHook")
class CollectionOwnershipValidationHookTest {

    private JdbcTemplate jdbcTemplate;
    private CollectionOwnershipValidationHook hook;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        hook = new CollectionOwnershipValidationHook(jdbcTemplate);
    }

    private static Map<String, Object> record(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private void usersLookup(String collectionId, String field, int count) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(collectionId), eq(field)))
                .thenReturn(count);
    }

    private static String code(BeforeSaveResult result) {
        return result.getErrors().getFirst().code();
    }

    @Test
    @DisplayName("ownerScope set without an ownerField → 400 INVALID_OWNER_FIELD")
    void scopeWithoutOwnerFieldIsRejected() {
        BeforeSaveResult result = hook.beforeCreate(record("name", "lists", "ownerScope", "PORTAL"), "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(code(result)).isEqualTo(CollectionOwnershipValidationHook.INVALID_OWNER_FIELD);
        assertThat(result.getErrors().getFirst().field()).isEqualTo("ownerField");
    }

    @Test
    @DisplayName("createdBy is a valid owner field at create time")
    void createdByIsValidOnCreate() {
        assertThat(hook.beforeCreate(
                record("name", "lists", "ownerScope", "ALL", "ownerField", "createdBy"), "t1").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a lookup owner field cannot be set at create time (the field does not exist yet)")
    void lookupOwnerOnCreateIsRejected() {
        BeforeSaveResult result = hook.beforeCreate(
                record("name", "lists", "ownerScope", "PORTAL", "ownerField", "member"), "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(code(result)).isEqualTo(CollectionOwnershipValidationHook.INVALID_OWNER_FIELD);
    }

    @Test
    @DisplayName("update to a LOOKUP→users owner field is admitted")
    void usersLookupIsValid() {
        usersLookup("c1", "member", 1);

        assertThat(hook.beforeUpdate("c1", record("ownerScope", "PORTAL", "ownerField", "member"),
                record("name", "lists", "ownerScope", "NONE"), "t1").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("update to a field that is not a LOOKUP to users → 400 INVALID_OWNER_FIELD")
    void nonUsersFieldIsRejected() {
        usersLookup("c1", "label", 0);

        BeforeSaveResult result = hook.beforeUpdate("c1", record("ownerScope", "PORTAL", "ownerField", "label"),
                record("name", "lists"), "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(code(result)).isEqualTo(CollectionOwnershipValidationHook.INVALID_OWNER_FIELD);
    }

    @Test
    @DisplayName("turning on a scope validates the stored owner field")
    void scopeOnlyPatchValidatesStoredField() {
        usersLookup("c1", "member", 0);

        BeforeSaveResult result = hook.beforeUpdate("c1", record("ownerScope", "ALL"),
                record("name", "lists", "ownerField", "member", "ownerScope", "NONE"), "t1");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("an unknown ownerScope → 400 INVALID_OWNER_SCOPE")
    void unknownScopeIsRejected() {
        BeforeSaveResult result = hook.beforeCreate(record("name", "lists", "ownerScope", "EVERYONE"), "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(code(result)).isEqualTo(CollectionOwnershipValidationHook.INVALID_OWNER_SCOPE);
    }

    @Test
    @DisplayName("a lower-case scope is normalized for the column's CHECK constraint")
    void lowerCaseScopeIsNormalized() {
        BeforeSaveResult result = hook.beforeCreate(
                record("name", "lists", "ownerScope", "portal", "ownerField", "createdBy"), "t1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFieldUpdates()).containsEntry("ownerScope", "PORTAL");
    }

    @Test
    @DisplayName("system collections cannot be given ownership through their row")
    void systemCollectionIsRejected() {
        BeforeSaveResult result = hook.beforeUpdate("c1", record("ownerScope", "PORTAL", "ownerField", "createdBy"),
                record("name", "notes", "systemCollection", true), "t1");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("updates that do not touch ownership are not re-validated")
    void unrelatedUpdateIsIgnored() {
        assertThat(hook.beforeUpdate("c1", record("displayName", "Lists"),
                record("name", "lists", "ownerScope", "PORTAL"), "t1").isSuccess()).isTrue();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("NONE (or absent) scope needs no owner field")
    void noneScopeIsValid() {
        assertThat(hook.beforeCreate(record("name", "lists"), "t1").isSuccess()).isTrue();
        assertThat(hook.beforeCreate(record("name", "lists", "ownerScope", "NONE"), "t1").isSuccess()).isTrue();
    }
}
