package io.kelta.runtime.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BeforeSaveResult")
class BeforeSaveResultTest {

    @Test
    @DisplayName("Should create OK result")
    void shouldCreateOkResult() {
        BeforeSaveResult result = BeforeSaveResult.ok();
        assertTrue(result.isSuccess());
        assertFalse(result.hasErrors());
        assertFalse(result.hasFieldUpdates());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getFieldUpdates().isEmpty());
    }

    @Test
    @DisplayName("Should create result with field updates")
    void shouldCreateWithFieldUpdates() {
        Map<String, Object> updates = Map.of("status", "ACTIVE", "version", 2);
        BeforeSaveResult result = BeforeSaveResult.withFieldUpdates(updates);

        assertTrue(result.isSuccess());
        assertFalse(result.hasErrors());
        assertTrue(result.hasFieldUpdates());
        assertEquals("ACTIVE", result.getFieldUpdates().get("status"));
        assertEquals(2, result.getFieldUpdates().get("version"));
    }

    @Test
    @DisplayName("Should create single error result")
    void shouldCreateSingleError() {
        BeforeSaveResult result = BeforeSaveResult.error("email", "Invalid email format");

        assertFalse(result.isSuccess());
        assertTrue(result.hasErrors());
        assertFalse(result.hasFieldUpdates());
        assertEquals(1, result.getErrors().size());
        assertEquals("email", result.getErrors().get(0).field());
        assertEquals("Invalid email format", result.getErrors().get(0).message());
    }

    @Test
    @DisplayName("Should create record-level error with null field")
    void shouldCreateRecordLevelError() {
        BeforeSaveResult result = BeforeSaveResult.error(null, "Record is locked");

        assertFalse(result.isSuccess());
        assertTrue(result.hasErrors());
        assertNull(result.getErrors().get(0).field());
        assertEquals("Record is locked", result.getErrors().get(0).message());
    }

    @Test
    @DisplayName("Should create multiple errors result")
    void shouldCreateMultipleErrors() {
        List<BeforeSaveResult.ValidationError> errors = List.of(
            new BeforeSaveResult.ValidationError("name", "Name is required"),
            new BeforeSaveResult.ValidationError("email", "Invalid email")
        );
        BeforeSaveResult result = BeforeSaveResult.errors(errors);

        assertFalse(result.isSuccess());
        assertTrue(result.hasErrors());
        assertEquals(2, result.getErrors().size());
        assertEquals("name", result.getErrors().get(0).field());
        assertEquals("email", result.getErrors().get(1).field());
    }

    @Test
    @DisplayName("Should handle null field updates in constructor")
    void shouldHandleNullFieldUpdates() {
        // ok() internally passes Map.of() so getFieldUpdates should never be null
        BeforeSaveResult result = BeforeSaveResult.ok();
        assertNotNull(result.getFieldUpdates());
    }

    @Test
    @DisplayName("Should handle null errors in constructor")
    void shouldHandleNullErrors() {
        // ok() internally passes List.of() so getErrors should never be null
        BeforeSaveResult result = BeforeSaveResult.ok();
        assertNotNull(result.getErrors());
    }

    @Test
    @DisplayName("ValidationError record should have correct fields")
    void validationErrorShouldHaveCorrectFields() {
        BeforeSaveResult.ValidationError error = new BeforeSaveResult.ValidationError("field1", "msg1");
        assertEquals("field1", error.field());
        assertEquals("msg1", error.message());
        assertNull(error.code(), "the 2-arg constructor should default code to null");
    }

    @Test
    @DisplayName("ValidationError record should carry an explicit code when given")
    void validationErrorShouldCarryExplicitCode() {
        BeforeSaveResult.ValidationError error =
            new BeforeSaveResult.ValidationError("field1", "msg1", "SOME_CODE");
        assertEquals("SOME_CODE", error.code());
    }
}
