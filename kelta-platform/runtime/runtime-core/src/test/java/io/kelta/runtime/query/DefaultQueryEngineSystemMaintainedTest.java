package io.kelta.runtime.query;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationEngine;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KLT-609: a {@link FieldDefinition#systemMaintained() system-maintained} field refuses API
 * callers (a bound {@link CallerContext}) and accepts the internal tier (no caller bound).
 */
@DisplayName("DefaultQueryEngine — system-maintained fields")
class DefaultQueryEngineSystemMaintainedTest {

    private static final CallerContext CALLER =
            new CallerContext("user-1", CallerContext.UserType.INTERNAL, true, true);

    private StorageAdapter storageAdapter;
    private DefaultQueryEngine engine;
    private CollectionDefinition inspections;

    @BeforeEach
    void setUp() {
        storageAdapter = mock(StorageAdapter.class);
        ValidationEngine validationEngine = mock(ValidationEngine.class);
        when(validationEngine.validate(any(), any(), any())).thenReturn(ValidationResult.success());
        when(validationEngine.validate(any(), any(), any(), any())).thenReturn(ValidationResult.success());
        engine = new DefaultQueryEngine(storageAdapter, validationEngine);

        inspections = new CollectionDefinitionBuilder()
                .name("inspections")
                .displayName("Inspections")
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.doubleField("count").withSystemMaintained(true))
                .addField(FieldDefinition.datetime("lastCountedAt").withSystemMaintained(true))
                .build();

        when(storageAdapter.create(eq(inspections), anyMap()))
                .thenAnswer(inv -> new HashMap<>(inv.<Map<String, Object>>getArgument(1)));
        when(storageAdapter.update(eq(inspections), eq("r1"), anyMap()))
                .thenAnswer(inv -> Optional.of(new HashMap<>(inv.<Map<String, Object>>getArgument(2))));
    }

    private void stored(Map<String, Object> row) {
        Map<String, Object> copy = new HashMap<>(row);
        copy.put("id", "r1");
        when(storageAdapter.getById(inspections, "r1")).thenReturn(Optional.of(copy));
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("a caller's create that sets the field is rejected, naming the collection and field")
    void callerCreateRejected() {
        ValidationException ex = assertThrows(ValidationException.class, () ->
                CallerContext.runAs(CALLER, () -> engine.create(inspections, data("name", "a", "count", 3))));

        assertThat(ex.getMessage()).contains("'count'").contains("'inspections'").contains("system-maintained");
        FieldError error = ex.getValidationResult().errors().get(0);
        assertThat(error.fieldName()).isEqualTo("count");
        assertThat(error.constraint()).isEqualTo("systemMaintained");
        verify(storageAdapter, never()).create(any(), any());
    }

    @Test
    @DisplayName("a caller's create that leaves the field out, or sends null, is accepted")
    void callerCreateWithoutValueAccepted() {
        CallerContext.runAs(CALLER, () -> {
            engine.create(inspections, data("name", "a"));
            engine.create(inspections, data("name", "b", "count", null));
        });

        verify(storageAdapter, org.mockito.Mockito.times(2)).create(eq(inspections), anyMap());
    }

    @Test
    @DisplayName("a caller's update that changes the field is rejected and nothing is written")
    void callerUpdateChangingValueRejected() {
        stored(data("name", "a", "count", 3.0));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                CallerContext.runAs(CALLER, () -> engine.update(inspections, "r1", data("count", 0))));

        assertThat(ex.getMessage()).contains("'count'").contains("'inspections'");
        verify(storageAdapter, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("a caller's update that clears the field is rejected")
    void callerUpdateClearingValueRejected() {
        stored(data("name", "a", "count", 3.0));

        assertThrows(ValidationException.class, () ->
                CallerContext.runAs(CALLER, () -> engine.update(inspections, "r1", data("count", null))));
    }

    @Test
    @DisplayName("a caller's update carrying the unchanged stored value is accepted and the field is not rewritten")
    @SuppressWarnings("unchecked")
    void callerUpdateWithUnchangedValueAccepted() {
        Instant countedAt = Instant.parse("2026-10-01T12:00:00Z");
        stored(data("name", "a", "count", 3.0, "lastCountedAt", countedAt));

        // The read-back forms differ from what a client sends: 3 vs 3.0, ISO string vs Instant.
        Optional<Map<String, Object>> result = CallerContext.callAs(CALLER, () -> engine.update(inspections, "r1",
                data("name", "renamed", "count", 3, "lastCountedAt", "2026-10-01T12:00:00Z")));

        assertThat(result).isPresent();
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(storageAdapter).update(eq(inspections), eq("r1"), patch.capture());
        assertThat(patch.getValue()).containsEntry("name", "renamed")
                .doesNotContainKey("count").doesNotContainKey("lastCountedAt");
    }

    @Test
    @DisplayName("with no caller bound (flows, listeners, schedulers) create and update write the field")
    @SuppressWarnings("unchecked")
    void internalTierWritesField() {
        assertThat(CallerContext.current()).isEmpty();

        engine.create(inspections, data("name", "a", "count", 1));
        stored(data("name", "a", "count", 1.0));
        engine.update(inspections, "r1", data("count", 2));

        ArgumentCaptor<Map<String, Object>> created = ArgumentCaptor.forClass(Map.class);
        verify(storageAdapter).create(eq(inspections), created.capture());
        assertThat(((Number) created.getValue().get("count")).doubleValue()).isEqualTo(1.0);
        ArgumentCaptor<Map<String, Object>> updated = ArgumentCaptor.forClass(Map.class);
        verify(storageAdapter).update(eq(inspections), eq("r1"), updated.capture());
        assertThat(((Number) updated.getValue().get("count")).doubleValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a collection without system-maintained fields is unaffected for callers")
    void ordinaryFieldsUnaffected() {
        CollectionDefinition plain = new CollectionDefinitionBuilder()
                .name("plain").displayName("Plain")
                .addField(FieldDefinition.doubleField("count"))
                .build();
        when(storageAdapter.create(eq(plain), anyMap())).thenAnswer(inv -> new HashMap<>(inv.<Map<String, Object>>getArgument(1)));

        CallerContext.runAs(CALLER, () -> engine.create(plain, data("count", 3)));

        verify(storageAdapter).create(eq(plain), anyMap());
    }
}
