package io.kelta.runtime.module.schema.hooks;

import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CollectionLifecycleHook")
class CollectionLifecycleHookTest {

    private final CollectionLifecycleHook hook = new CollectionLifecycleHook();

    @Test
    @DisplayName("Should target 'collections' collection")
    void shouldTargetCollections() {
        assertEquals("collections", hook.getCollectionName());
    }

    @Nested
    @DisplayName("beforeCreate")
    class BeforeCreate {

        @Test
        @DisplayName("Should reject missing name")
        void shouldRejectMissingName() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(), "t1");
            assertFalse(result.isSuccess());
            assertEquals("name", result.getErrors().get(0).field());
        }

        @Test
        @DisplayName("Should reject blank name")
        void shouldRejectBlankName() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "  ")), "t1");
            assertFalse(result.isSuccess());
            assertEquals("name", result.getErrors().get(0).field());
        }

        @Test
        @DisplayName("Should reject uppercase name")
        void shouldRejectUppercaseName() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "MyCollection")), "t1");
            assertFalse(result.isSuccess());
            assertEquals("name", result.getErrors().get(0).field());
        }

        @Test
        @DisplayName("Should reject name starting with number")
        void shouldRejectNameStartingWithNumber() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "123abc")), "t1");
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should accept name with underscores")
        void shouldAcceptNameWithUnderscores() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "my_collection")), "t1");
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should accept multi-word underscore names like technician_skills")
        void shouldAcceptMultiWordUnderscoreNames() {
            for (String name : List.of("technician_skills", "quote_lines", "invoice_lines", "work_orders")) {
                BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", name)), "t1");
                assertTrue(result.isSuccess(), "Expected name '" + name + "' to be accepted");
            }
        }

        @Test
        @DisplayName("Should accept valid lowercase hyphenated name")
        void shouldAcceptValidName() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "my-collection")), "t1");
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should reject name with special characters")
        void shouldRejectNameWithSpecialChars() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "my.collection")), "t1");
            assertFalse(result.isSuccess());
            assertEquals("name", result.getErrors().get(0).field());
        }

        @Test
        @DisplayName("Should set default currentVersion to 1")
        void shouldSetDefaultVersion() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "tasks")), "t1");
            assertTrue(result.isSuccess());
            assertTrue(result.hasFieldUpdates());
            assertEquals(1L, result.getFieldUpdates().get("currentVersion"));
        }

        @Test
        @DisplayName("Should not override existing currentVersion")
        void shouldNotOverrideExistingVersion() {
            Map<String, Object> record = new HashMap<>(Map.of("name", "tasks", "currentVersion", 5L));
            BeforeSaveResult result = hook.beforeCreate(record, "t1");
            assertTrue(result.isSuccess());
            assertNull(result.getFieldUpdates().get("currentVersion"));
        }

        @Test
        @DisplayName("Should set default active to true")
        void shouldSetDefaultActive() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "tasks")), "t1");
            assertTrue(result.isSuccess());
            assertEquals(true, result.getFieldUpdates().get("active"));
        }

        @Test
        @DisplayName("Should generate API path from name")
        void shouldGenerateApiPath() {
            BeforeSaveResult result = hook.beforeCreate(new HashMap<>(Map.of("name", "tasks")), "t1");
            assertTrue(result.isSuccess());
            assertEquals("/api/tasks", result.getFieldUpdates().get("path"));
        }

        @Test
        @DisplayName("Should not override existing path")
        void shouldNotOverrideExistingPath() {
            Map<String, Object> record = new HashMap<>(Map.of("name", "tasks", "path", "/custom/path"));
            BeforeSaveResult result = hook.beforeCreate(record, "t1");
            assertTrue(result.isSuccess());
            assertNull(result.getFieldUpdates().get("path"));
        }

        @Test
        @DisplayName("Should return ok when all defaults already present")
        void shouldReturnOkWhenDefaultsPresent() {
            Map<String, Object> record = new HashMap<>(Map.of(
                "name", "tasks",
                "currentVersion", 1L,
                "active", true,
                "path", "/api/tasks"
            ));
            BeforeSaveResult result = hook.beforeCreate(record, "t1");
            assertTrue(result.isSuccess());
            assertFalse(result.hasFieldUpdates());
        }
    }

    @Nested
    @DisplayName("beforeUpdate")
    class BeforeUpdate {

        private final Map<String, Object> stored = Map.of("name", "tasks", "displayName", "Tasks");

        @Test
        @DisplayName("Should reject a change of name")
        void shouldRejectRename() {
            BeforeSaveResult result = hook.beforeUpdate("c1", new HashMap<>(Map.of("name", "jobs")), stored, "t1");
            assertFalse(result.isSuccess());
            assertEquals("name", result.getErrors().get(0).field());
        }

        @Test
        @DisplayName("Should allow the unchanged name to be sent back")
        void shouldAllowSameName() {
            assertTrue(hook.beforeUpdate("c1", new HashMap<>(Map.of("name", " tasks ", "displayName", "Jobs")),
                    stored, "t1").isSuccess());
        }

        @Test
        @DisplayName("Should allow updates that do not touch the name")
        void shouldAllowOtherUpdates() {
            assertTrue(hook.beforeUpdate("c1", new HashMap<>(Map.of("displayName", "Jobs")), stored, "t1")
                    .isSuccess());
        }

        @Test
        @DisplayName("Should refuse turning a tenant collection into a system collection")
        void shouldRefusePromotionToSystemCollection() {
            BeforeSaveResult result = hook.beforeUpdate("c1",
                    new HashMap<>(Map.of("systemCollection", true)),
                    Map.of("name", "tasks", "systemCollection", false), "t1");
            assertFalse(result.isSuccess());
            assertEquals("systemCollection", result.getErrors().get(0).field());
            assertEquals("systemMaintained", result.getErrors().get(0).code());
        }

        @Test
        @DisplayName("Should refuse clearing the flag on a system collection")
        void shouldRefuseDemotionOfSystemCollection() {
            BeforeSaveResult result = hook.beforeUpdate("c1",
                    new HashMap<>(Map.of("systemCollection", false)),
                    Map.of("name", "users", "systemCollection", true), "t1");
            assertFalse(result.isSuccess());
            assertEquals("systemCollection", result.getErrors().get(0).field());
        }

        @Test
        @DisplayName("Should allow the stored systemCollection value to be sent back")
        void shouldAllowUnchangedSystemCollectionFlag() {
            assertTrue(hook.beforeUpdate("c1",
                    new HashMap<>(Map.of("systemCollection", false, "displayName", "Jobs")),
                    Map.of("name", "tasks", "systemCollection", false), "t1").isSuccess());
            assertTrue(hook.beforeUpdate("c1",
                    new HashMap<>(Map.of("systemCollection", true, "displayName", "Users")),
                    Map.of("name", "users", "systemCollection", true), "t1").isSuccess());
        }

        @Test
        @DisplayName("Should treat a stored row without the flag as a tenant collection")
        void shouldTreatMissingStoredFlagAsFalse() {
            assertTrue(hook.beforeUpdate("c1", new HashMap<>(Map.of("systemCollection", false)), stored, "t1")
                    .isSuccess());
            assertFalse(hook.beforeUpdate("c1", new HashMap<>(Map.of("systemCollection", "true")), stored, "t1")
                    .isSuccess());
        }
    }

    @Nested
    @DisplayName("systemCollection on create")
    class SystemCollectionOnCreate {

        @Test
        @DisplayName("Should refuse systemCollection: true")
        void shouldRefuseSystemCollectionTrue() {
            BeforeSaveResult result = hook.beforeCreate(
                    new HashMap<>(Map.of("name", "tasks", "systemCollection", true)), "t1");
            assertFalse(result.isSuccess());
            assertEquals("systemCollection", result.getErrors().get(0).field());
            assertEquals("systemMaintained", result.getErrors().get(0).code());
        }

        @Test
        @DisplayName("Should refuse the string form of true")
        void shouldRefuseSystemCollectionTrueString() {
            assertFalse(hook.beforeCreate(
                    new HashMap<>(Map.of("name", "tasks", "systemCollection", " TRUE ")), "t1").isSuccess());
        }

        @Test
        @DisplayName("Should allow an explicit systemCollection: false")
        void shouldAllowSystemCollectionFalse() {
            assertTrue(hook.beforeCreate(
                    new HashMap<>(Map.of("name", "tasks", "systemCollection", false)), "t1").isSuccess());
        }
    }
}
