package io.kelta.worker.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ValidationRules;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.worker.repository.PicklistValueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CollectionLifecycleManager.refreshOrInitializeLocally (issue #910)")
class CollectionLifecycleManagerTest {

    private CollectionLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = spy(new CollectionLifecycleManager(
                mock(CollectionRegistry.class),
                mock(StorageAdapter.class),
                mock(JdbcTemplate.class),
                mock(ObjectMapper.class)));
    }

    @Test
    @DisplayName("Refreshes when the collection is already active on this pod")
    void refreshesWhenActive() {
        doReturn(Set.of("col-1")).when(manager).getActiveCollections();
        doNothing().when(manager).refreshCollection("col-1");

        manager.refreshOrInitializeLocally("col-1");

        verify(manager).refreshCollection("col-1");
        verify(manager, never()).initializeCollection("col-1");
    }

    @Test
    @DisplayName("Initializes when the collection is not yet active (freshly created)")
    void initializesWhenNotActive() {
        doReturn(Set.of()).when(manager).getActiveCollections();
        doNothing().when(manager).initializeCollection("col-2");

        manager.refreshOrInitializeLocally("col-2");

        verify(manager).initializeCollection("col-2");
        verify(manager, never()).refreshCollection("col-2");
    }

    @Test
    @DisplayName("Swallows refresh failure so the originating request never breaks")
    void swallowsFailure() {
        doReturn(Set.of("col-3")).when(manager).getActiveCollections();
        doThrow(new RuntimeException("db down")).when(manager).refreshCollection("col-3");

        assertDoesNotThrow(() -> manager.refreshOrInitializeLocally("col-3"));
    }

    @Nested
    @DisplayName("field.system_maintained → FieldDefinition.systemMaintained (KLT-609)")
    class SystemMaintainedMapping {

        private static Map<String, Object> fieldRow(String name, Object systemMaintained) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", name);
            row.put("type", "number");
            row.put("system_maintained", systemMaintained);
            return row;
        }

        @Test
        @DisplayName("reads system_maintained into the field definition, defaulting to false")
        void mapsSystemMaintainedColumn() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            CollectionLifecycleManager loader = new CollectionLifecycleManager(
                    mock(CollectionRegistry.class), mock(StorageAdapter.class), jdbcTemplate, new ObjectMapper());
            when(jdbcTemplate.queryForList(contains("system_maintained"), eq("col-1"))).thenReturn(List.of(
                    fieldRow("openCount", true),
                    fieldRow("score", false),
                    fieldRow("legacy", null)));

            CollectionDefinition def = loader.buildDefinitionFromDb("col-1", "inspections", Map.of());

            assertThat(def.getField("openCount").systemMaintained()).isTrue();
            assertThat(def.getField("score").systemMaintained()).isFalse();
            assertThat(def.getField("legacy").systemMaintained()).isFalse();
            assertThat(def.systemMaintainedFieldNames()).containsExactly("openCount");
        }
    }

    @Nested
    @DisplayName("field.constraints + picklist values → ValidationRules / enumValues (PLT-711)")
    class ConstraintMapping {

        private static final String TENANT = "tenant-1";

        private JdbcTemplate jdbcTemplate;
        private CollectionLifecycleManager loader;
        private ch.qos.logback.classic.Logger managerLogger;
        private ListAppender<ILoggingEvent> logAppender;

        @BeforeEach
        void setUp() {
            jdbcTemplate = mock(JdbcTemplate.class);
            loader = new CollectionLifecycleManager(
                    mock(CollectionRegistry.class), mock(StorageAdapter.class), jdbcTemplate, new ObjectMapper());
            loader.setPicklistValueRepository(new PicklistValueRepository(jdbcTemplate));
            managerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CollectionLifecycleManager.class);
            logAppender = new ListAppender<>();
            logAppender.start();
            managerLogger.addAppender(logAppender);
        }

        @AfterEach
        void tearDown() {
            managerLogger.detachAppender(logAppender);
        }

        private Map<String, Object> fieldRow(String name, String type, Object constraints) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", name);
            row.put("type", type);
            row.put("constraints", constraints);
            return row;
        }

        private static Map<String, Object> valueRow(String field, String value, int sortOrder) {
            Map<String, Object> row = new HashMap<>();
            row.put("field_name", field);
            row.put("value", value);
            row.put("label", value.toUpperCase());
            row.put("is_default", false);
            row.put("sort_order", sortOrder);
            return row;
        }

        private CollectionDefinition load(List<Map<String, Object>> fieldRows) {
            when(jdbcTemplate.queryForList(contains("system_maintained"), eq("col-1"))).thenReturn(fieldRows);
            return loader.buildDefinitionFromDb("col-1", "things", Map.of("tenant_id", TENANT));
        }

        private List<ILoggingEvent> warnings() {
            return logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        }

        @Test
        @DisplayName("STRING max 3 + pattern (field-editor JSON string) → maxLength 3 and the pattern")
        void stringMaxLengthAndPattern() {
            CollectionDefinition def = load(List.of(
                    fieldRow("code", "string", "{\"max\":3,\"pattern\":\"^[a-z]+$\"}")));

            FieldDefinition code = def.getField("code");
            assertThat(code.validationRules()).isEqualTo(new ValidationRules(null, null, null, 3, "^[a-z]+$"));
            assertThat(code.enumValues()).isNull();
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("number min/max → minValue/maxValue; runtime names map as named")
        void numberMinMaxAreValueBounds() {
            CollectionDefinition def = load(List.of(
                    fieldRow("amount", "number", Map.of("min", 1, "max", 10)),
                    fieldRow("score", "INTEGER", Map.of("minValue", 0, "maxValue", 5.5)),
                    fieldRow("title", "string", Map.of("minLength", 2, "maxLength", "40"))));

            assertThat(def.getField("amount").type()).isEqualTo(FieldType.DOUBLE);
            assertThat(def.getField("amount").validationRules()).isEqualTo(ValidationRules.forNumeric(1.0, 10.0));
            assertThat(def.getField("score").validationRules()).isEqualTo(ValidationRules.forNumeric(0.0, 5.5));
            assertThat(def.getField("title").validationRules()).isEqualTo(ValidationRules.forString(2, 40));
        }

        @Test
        @DisplayName("PICKLIST enumValues = the active values from one per-collection query")
        void picklistEnumValuesFromActiveValues() {
            when(jdbcTemplate.queryForList(PicklistValueRepository.SELECT_ACTIVE_VALUES_BY_COLLECTION, TENANT, "things"))
                    .thenReturn(List.of(valueRow("status", "a", 0), valueRow("status", "b", 1)));

            CollectionDefinition def = load(List.of(
                    fieldRow("status", "picklist", null),
                    fieldRow("note", "string", null)));

            assertThat(PicklistValueRepository.SELECT_ACTIVE_VALUES_BY_COLLECTION).contains("pv.is_active = true");
            assertThat(def.getField("status").enumValues()).containsExactly("a", "b");
            assertThat(def.getField("status").validationRules()).isNull();
            assertThat(def.getField("note").enumValues()).isNull();
            verify(jdbcTemplate).queryForList(PicklistValueRepository.SELECT_ACTIVE_VALUES_BY_COLLECTION, TENANT, "things");
        }

        @Test
        @DisplayName("no picklist field → no picklist value query")
        void noPicklistFieldSkipsValueQuery() {
            load(List.of(fieldRow("note", "string", null)));

            verify(jdbcTemplate, never()).queryForList(eq(PicklistValueRepository.SELECT_ACTIVE_VALUES_BY_COLLECTION),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("a picklist field without values falls back to a stored constraints.enumValues")
        void storedEnumValuesFallback() {
            CollectionDefinition def = load(List.of(
                    fieldRow("tier", "picklist", Map.of("enumValues", List.of("gold", "silver")))));

            assertThat(def.getField("tier").enumValues()).containsExactly("gold", "silver");
        }

        @Test
        @DisplayName("email/url are ignored with one WARN and the rest still maps; the load does not fail")
        void unsupportedKeysIgnoredWithOneWarn() {
            CollectionDefinition def = load(List.of(
                    fieldRow("contact", "string", Map.of("max", 50, "email", true, "url", true))));

            assertThat(def.getField("contact").validationRules()).isEqualTo(ValidationRules.forString(null, 50));
            assertThat(warnings()).singleElement().satisfies(w -> assertThat(w.getFormattedMessage())
                    .contains("contact").contains("email").contains("url"));
        }

        @Test
        @DisplayName("DATE min/max are not length bounds: ignored with a WARN")
        void dateMinMaxIgnored() {
            CollectionDefinition def = load(List.of(fieldRow("due", "date", Map.of("min", 1, "max", 5))));

            assertThat(def.getField("due").validationRules()).isNull();
            assertThat(warnings()).singleElement().satisfies(w -> assertThat(w.getFormattedMessage())
                    .contains("due").contains("min").contains("max"));
        }

        @Test
        @DisplayName("malformed constraints are ignored rather than failing the load")
        void malformedConstraintsIgnored() {
            CollectionDefinition def = load(List.of(
                    fieldRow("broken", "string", "{not json"),
                    fieldRow("badMax", "string", Map.of("max", "lots"))));

            assertThat(def.getField("broken").validationRules()).isNull();
            assertThat(def.getField("badMax").validationRules()).isNull();
            assertThat(warnings()).hasSize(2);
        }
    }
}
