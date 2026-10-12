package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReservedCollectionNameHook")
class ReservedCollectionNameHookTest {

    private final ReservedCollectionNameHook hook = new ReservedCollectionNameHook();

    private static Map<String, Object> record(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @ParameterizedTest
    @ValueSource(strings = {"track", "webhooks", "reports", "files", "users", "admin"})
    @DisplayName("a name the gateway serves as a static route is rejected on create")
    void staticSegmentNameIsRejected(String name) {
        BeforeSaveResult result = hook.beforeCreate(record("name", name), "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("name");
        assertThat(result.getErrors().getFirst().code())
                .isEqualTo(ReservedCollectionNameHook.RESERVED_COLLECTION_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tracks", "tracking", "webhook-logs", "my-reports", "orders"})
    @DisplayName("a name that only shares leading characters with a static route is allowed")
    void similarNameIsAllowed(String name) {
        assertThat(hook.beforeCreate(record("name", name), "t1").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("surrounding whitespace does not get a reserved name through")
    void whitespaceIsTrimmed() {
        assertThat(hook.beforeCreate(record("name", " reports "), "t1").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("system collection rows are left alone")
    void systemCollectionIsIgnored() {
        assertThat(hook.beforeCreate(record("name", "users", "systemCollection", true), "t1").isSuccess())
                .isTrue();
        assertThat(hook.beforeUpdate("c1", record("name", "reports"),
                record("name", "old", "systemCollection", true), "t1").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("renaming to a reserved name is rejected; other updates pass")
    void renameIsChecked() {
        assertThat(hook.beforeUpdate("c1", record("name", "reports"), record("name", "orders"), "t1")
                .isSuccess()).isFalse();
        assertThat(hook.beforeUpdate("c1", record("displayName", "Orders"), record("name", "orders"), "t1")
                .isSuccess()).isTrue();
        assertThat(hook.beforeUpdate("c1", record("name", "orders"), record("name", "orders"), "t1")
                .isSuccess()).isTrue();
    }

    @Test
    @DisplayName("an unchanged name that predates the check does not block other updates")
    void existingReservedNameIsNotReChecked() {
        assertThat(hook.beforeUpdate("c1", record("name", "reports", "displayName", "Reports"),
                record("name", "reports"), "t1").isSuccess()).isTrue();
    }
}
