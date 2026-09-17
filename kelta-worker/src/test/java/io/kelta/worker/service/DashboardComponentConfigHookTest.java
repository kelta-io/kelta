package io.kelta.worker.service;

import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DashboardComponentConfigHookTest {

    private DashboardComponentValidator validator;
    private DashboardComponentConfigHook hook;

    @BeforeEach
    void setUp() {
        validator = mock(DashboardComponentValidator.class);
        hook = new DashboardComponentConfigHook(validator);
    }

    @Test
    void targetsDashboardComponentsCollection() {
        assertEquals("dashboard-components", hook.getCollectionName());
    }

    @Test
    void beforeCreatePassesWhenValidatorReturnsNoErrors() {
        Map<String, Object> record = Map.of("componentType", "metric");
        when(validator.validate(record)).thenReturn(List.of());

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertTrue(result.isSuccess());
    }

    @Test
    void beforeCreateFailsWithValidatorErrors() {
        Map<String, Object> record = Map.of("componentType", "chart");
        when(validator.validate(record)).thenReturn(
            List.of(new BeforeSaveResult.ValidationError("config/collectionName", "Unknown collection 'nope'")));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertEquals(1, result.getErrors().size());
        assertEquals("config/collectionName", result.getErrors().get(0).field());
        assertNotEquals("beforeSaveHook", result.getErrors().get(0).code());
    }

    @Test
    void beforeUpdateValidatesMergedEffectiveRecord() {
        Map<String, Object> previous = new HashMap<>();
        previous.put("componentType", "metric");
        previous.put("config", Map.of("collectionName", "accounts"));
        previous.put("columnPosition", 1);

        Map<String, Object> partialUpdate = Map.of("title", "New title");

        when(validator.validate(any())).thenReturn(List.of());

        BeforeSaveResult result = hook.beforeUpdate("comp-1", partialUpdate, previous, "tenant-1");

        assertTrue(result.isSuccess());
        verify(validator).validate(argThat(merged ->
            "accounts".equals(((Map<?, ?>) merged.get("config")).get("collectionName"))
                && "New title".equals(merged.get("title"))));
    }
}
