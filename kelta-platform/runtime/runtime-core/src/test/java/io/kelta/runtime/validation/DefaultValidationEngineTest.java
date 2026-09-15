package io.kelta.runtime.validation;

import io.kelta.runtime.model.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultValidationEngine}.
 * 
 * Validates: Requirements 11.1-11.10, 15.4
 */
@DisplayName("DefaultValidationEngine Tests")
class DefaultValidationEngineTest {

    private StorageAdapter storageAdapter;
    private CollectionRegistry collectionRegistry;
    private DefaultValidationEngine validationEngine;
    
    @BeforeEach
    void setUp() {
        storageAdapter = mock(StorageAdapter.class);
        collectionRegistry = mock(CollectionRegistry.class);
        validationEngine = new DefaultValidationEngine(storageAdapter, collectionRegistry);
        
        // Default mock behavior - unique values are unique
        when(storageAdapter.isUnique(any(), anyString(), any(), any())).thenReturn(true);
    }

    private CollectionDefinition createTestCollection(FieldDefinition... fields) {
        return new CollectionDefinition(
            "test_collection",
            "Test Collection",
            "A test collection",
            List.of(fields),
            new StorageConfig("tbl_test", Map.of()),
            new ApiConfig(true, true, true, true, true, "/api/test"),
            new AuthzConfig(false, List.of(), List.of()),
            1L,
            Instant.now(),
            Instant.now()
        );
    }

    @Nested
    @DisplayName("Nullable Constraint Tests - Requirement 11.5")
    class NullableConstraintTests {

        @Test
        @DisplayName("Should reject null value for non-nullable field")
        void shouldRejectNullForNonNullableField() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.requiredString("name")
            );
            
            Map<String, Object> data = new HashMap<>();
            data.put("name", null);
            
            ValidationResult result = validationEngine.validate(definition, data, OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals(1, result.errorCount());
            assertTrue(result.hasErrorsForField("name"));
            assertEquals("nullable", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept null value for nullable field")
        void shouldAcceptNullForNullableField() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.string("name")
            );
            
            Map<String, Object> data = new HashMap<>();
            data.put("name", null);
            
            ValidationResult result = validationEngine.validate(definition, data, OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should reject missing non-nullable field on CREATE")
        void shouldRejectMissingNonNullableFieldOnCreate() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.requiredString("name")
            );
            
            Map<String, Object> data = new HashMap<>();
            // name field is not provided
            
            ValidationResult result = validationEngine.validate(definition, data, OperationType.CREATE);
            
            assertFalse(result.valid());
            assertTrue(result.hasErrorsForField("name"));
        }
    }

    @Nested
    @DisplayName("Type Validation Tests")
    class TypeValidationTests {

        @Test
        @DisplayName("Should accept valid string type")
        void shouldAcceptValidStringType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.string("name")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("name", "John"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should reject invalid string type")
        void shouldRejectInvalidStringType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.requiredString("name")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("name", 123), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("type", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept valid integer type")
        void shouldAcceptValidIntegerType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.integer("count")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("count", 42), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept valid long type")
        void shouldAcceptValidLongType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.longField("bigNumber")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("bigNumber", 9999999999L), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept integer as long type")
        void shouldAcceptIntegerAsLongType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.longField("bigNumber")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("bigNumber", 42), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept valid double type")
        void shouldAcceptValidDoubleType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.doubleField("price")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("price", 99.99), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept integer as double type")
        void shouldAcceptIntegerAsDoubleType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.doubleField("price")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("price", 100), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept valid boolean type")
        void shouldAcceptValidBooleanType() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.bool("active")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("active", true), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should reject string as boolean type")
        void shouldRejectStringAsBooleanType() {
            CollectionDefinition definition = createTestCollection(
                new FieldDefinition("active", FieldType.BOOLEAN, false, false, false, null, null, null, null, null)
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("active", "true"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("type", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept valid date string")
        void shouldAcceptValidDateString() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.date("birthDate")
            );

            ValidationResult result = validationEngine.validate(
                definition, Map.of("birthDate", "2024-01-15"), OperationType.CREATE);

            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept a DATE column's stored read-back form (midnight datetime + java.util.Date)")
        void shouldAcceptStoredDateReadBackForms() {
            CollectionDefinition definition = createTestCollection(FieldDefinition.date("issueDate"));

            // ISO datetime at midnight — how a DATE column reads back through the JSON layer.
            assertTrue(validationEngine.validate(
                definition, Map.of("issueDate", "2026-07-12T00:00:00.000Z"), OperationType.UPDATE).valid());
            assertTrue(validationEngine.validate(
                definition, Map.of("issueDate", "2026-07-12T00:00:00"), OperationType.UPDATE).valid());
            // JDBC read-back type.
            assertTrue(validationEngine.validate(
                definition, Map.of("issueDate", java.sql.Date.valueOf("2026-07-12")), OperationType.UPDATE).valid());
        }

        @Test
        @DisplayName("Should reject invalid date string")
        void shouldRejectInvalidDateString() {
            CollectionDefinition definition = createTestCollection(
                new FieldDefinition("birthDate", FieldType.DATE, false, false, false, null, null, null, null, null)
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("birthDate", "not-a-date"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("type", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept valid datetime string")
        void shouldAcceptValidDatetimeString() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.datetime("createdAt")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("createdAt", "2024-01-15T10:30:00"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept ISO-8601 datetime with timezone")
        void shouldAcceptIso8601DatetimeWithTimezone() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.datetime("createdAt")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("createdAt", "2024-01-15T10:30:00Z"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept valid JSON map")
        void shouldAcceptValidJsonMap() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.json("metadata")
            );
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("metadata", Map.of("key", "value")), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept valid JSON list")
        void shouldAcceptValidJsonList() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.json("tags")
            );

            ValidationResult result = validationEngine.validate(
                definition, Map.of("tags", List.of("tag1", "tag2")), OperationType.CREATE);

            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should accept JSON scalar values (string, number, boolean)")
        void shouldAcceptJsonScalarValues() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.json("defaultValue")
            );

            assertTrue(validationEngine.validate(
                definition, Map.of("defaultValue", "en"), OperationType.CREATE).valid());
            assertTrue(validationEngine.validate(
                definition, Map.of("defaultValue", 30), OperationType.CREATE).valid());
            assertTrue(validationEngine.validate(
                definition, Map.of("defaultValue", true), OperationType.CREATE).valid());
        }

        @Test
        @DisplayName("Should still reject scalar values for GEOLOCATION")
        void shouldRejectScalarForGeolocation() {
            CollectionDefinition definition = createTestCollection(
                new FieldDefinition("position", FieldType.GEOLOCATION, true, false, false,
                    null, null, null, null, null)
            );

            ValidationResult result = validationEngine.validate(
                definition, Map.of("position", "38.7,-9.1"), OperationType.CREATE);

            assertFalse(result.valid());
            assertEquals("type", result.errors().get(0).constraint());
        }
    }

    @Nested
    @DisplayName("Min/Max Value Constraint Tests - Requirements 11.1, 11.2")
    class MinMaxValueConstraintTests {

        @Test
        @DisplayName("Should reject value below minimum - Requirement 11.1")
        void shouldRejectValueBelowMinimum() {
            FieldDefinition field = new FieldDefinition(
                "age", FieldType.INTEGER, false, false, false, null,
                ValidationRules.forNumeric(18.0, null), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("age", 15), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("minValue", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept value at minimum")
        void shouldAcceptValueAtMinimum() {
            FieldDefinition field = new FieldDefinition(
                "age", FieldType.INTEGER, false, false, false, null,
                ValidationRules.forNumeric(18.0, null), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("age", 18), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should reject value above maximum - Requirement 11.2")
        void shouldRejectValueAboveMaximum() {
            FieldDefinition field = new FieldDefinition(
                "score", FieldType.INTEGER, false, false, false, null,
                ValidationRules.forNumeric(null, 100.0), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("score", 150), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("maxValue", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept value at maximum")
        void shouldAcceptValueAtMaximum() {
            FieldDefinition field = new FieldDefinition(
                "score", FieldType.INTEGER, false, false, false, null,
                ValidationRules.forNumeric(null, 100.0), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("score", 100), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should validate min/max for double values")
        void shouldValidateMinMaxForDoubleValues() {
            FieldDefinition field = new FieldDefinition(
                "price", FieldType.DOUBLE, false, false, false, null,
                ValidationRules.forNumeric(0.0, 1000.0), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("price", -5.0), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("minValue", result.errors().get(0).constraint());
        }
    }

    @Nested
    @DisplayName("Min/Max Length Constraint Tests - Requirement 11.3")
    class MinMaxLengthConstraintTests {

        @Test
        @DisplayName("Should reject string below minimum length")
        void shouldRejectStringBelowMinimumLength() {
            FieldDefinition field = new FieldDefinition(
                "username", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(3, null), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("username", "ab"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("minLength", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept string at minimum length")
        void shouldAcceptStringAtMinimumLength() {
            FieldDefinition field = new FieldDefinition(
                "username", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(3, null), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("username", "abc"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should reject string exceeding maximum length")
        void shouldRejectStringExceedingMaximumLength() {
            FieldDefinition field = new FieldDefinition(
                "code", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(null, 5), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("code", "ABCDEF"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("maxLength", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept string at maximum length")
        void shouldAcceptStringAtMaximumLength() {
            FieldDefinition field = new FieldDefinition(
                "code", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(null, 5), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("code", "ABCDE"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }
    }

    @Nested
    @DisplayName("Pattern Constraint Tests - Requirement 11.4")
    class PatternConstraintTests {

        @Test
        @DisplayName("Should reject value not matching pattern")
        void shouldRejectValueNotMatchingPattern() {
            FieldDefinition field = new FieldDefinition(
                "email", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(null, null, "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"),
                null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("email", "not-an-email"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("pattern", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept value matching pattern")
        void shouldAcceptValueMatchingPattern() {
            FieldDefinition field = new FieldDefinition(
                "email", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(null, null, "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"),
                null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("email", "test@example.com"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should validate alphanumeric pattern")
        void shouldValidateAlphanumericPattern() {
            FieldDefinition field = new FieldDefinition(
                "sku", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(null, null, "^[A-Z0-9-]+$"),
                null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);
            
            // Valid SKU
            ValidationResult validResult = validationEngine.validate(
                definition, Map.of("sku", "ABC-123"), OperationType.CREATE);
            assertTrue(validResult.valid());
            
            // Invalid SKU (lowercase)
            ValidationResult invalidResult = validationEngine.validate(
                definition, Map.of("sku", "abc-123"), OperationType.CREATE);
            assertFalse(invalidResult.valid());
        }
    }

    @Nested
    @DisplayName("Immutable Constraint Tests - Requirement 11.6")
    class ImmutableConstraintTests {

        @Test
        @DisplayName("Should reject updates to immutable field")
        void shouldRejectUpdatesToImmutableField() {
            FieldDefinition field = new FieldDefinition(
                "createdBy", FieldType.STRING, false, true, false, null, null, null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("createdBy", "newUser"), OperationType.UPDATE);
            
            assertFalse(result.valid());
            assertEquals("immutable", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should allow immutable field on CREATE")
        void shouldAllowImmutableFieldOnCreate() {
            FieldDefinition field = new FieldDefinition(
                "createdBy", FieldType.STRING, false, true, false, null, null, null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("createdBy", "user123"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should skip immutable field not provided in UPDATE")
        void shouldSkipImmutableFieldNotProvidedInUpdate() {
            FieldDefinition immutableField = new FieldDefinition(
                "createdBy", FieldType.STRING, false, true, false, null, null, null, null, null
            );
            FieldDefinition mutableField = FieldDefinition.string("name");
            CollectionDefinition definition = createTestCollection(immutableField, mutableField);
            
            // Only updating the mutable field
            ValidationResult result = validationEngine.validate(
                definition, Map.of("name", "Updated Name"), OperationType.UPDATE);
            
            assertTrue(result.valid());
        }
    }

    @Nested
    @DisplayName("Unique Constraint Tests - Requirement 11.7")
    class UniqueConstraintTests {

        @Test
        @DisplayName("Should reject duplicate value for unique field")
        void shouldRejectDuplicateValueForUniqueField() {
            FieldDefinition field = new FieldDefinition(
                "email", FieldType.STRING, false, false, true, null, null, null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);
            
            // Mock storage adapter to return false (value is not unique)
            when(storageAdapter.isUnique(eq(definition), eq("email"), eq("test@example.com"), isNull()))
                .thenReturn(false);
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("email", "test@example.com"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("unique", result.errors().get(0).constraint());
        }

        @Test
        @DisplayName("Should accept unique value for unique field")
        void shouldAcceptUniqueValueForUniqueField() {
            FieldDefinition field = new FieldDefinition(
                "email", FieldType.STRING, false, false, true, null, null, null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);
            
            // Mock storage adapter to return true (value is unique)
            when(storageAdapter.isUnique(eq(definition), eq("email"), eq("unique@example.com"), isNull()))
                .thenReturn(true);
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("email", "unique@example.com"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("excludeId parameter reaches the uniqueness check when the data map has no id (patch-only update)")
        void excludeIdParameterReachesUniquenessCheck() {
            FieldDefinition field = new FieldDefinition(
                "name", FieldType.STRING, false, false, true, null, null, null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);

            // Re-sending the record's own unique value in a patch (no "id" key,
            // like a metadata-promotion upsert) must exclude the record itself
            // via the explicit excludeId parameter — the interface default used
            // to drop it, and self-exclusion silently relied on a merged map.
            when(storageAdapter.isUnique(eq(definition), eq("name"), eq("sbxcustomers"), eq("rec-1")))
                .thenReturn(true);

            ValidationResult result = validationEngine.validate(
                definition, Map.of("name", "sbxcustomers"), OperationType.UPDATE, "rec-1");

            assertTrue(result.valid());
            verify(storageAdapter).isUnique(eq(definition), eq("name"), eq("sbxcustomers"), eq("rec-1"));
        }

        @Test
        @DisplayName("Should exclude current record ID when checking uniqueness on UPDATE")
        void shouldExcludeCurrentRecordIdOnUpdate() {
            FieldDefinition field = new FieldDefinition(
                "email", FieldType.STRING, false, false, true, null, null, null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);
            
            String recordId = "record-123";
            
            // Mock storage adapter to return true when excluding the current record
            when(storageAdapter.isUnique(eq(definition), eq("email"), eq("test@example.com"), eq(recordId)))
                .thenReturn(true);
            
            Map<String, Object> data = new HashMap<>();
            data.put("id", recordId);
            data.put("email", "test@example.com");
            
            ValidationResult result = validationEngine.validate(definition, data, OperationType.UPDATE);
            
            assertTrue(result.valid());
            verify(storageAdapter).isUnique(definition, "email", "test@example.com", recordId);
        }
    }

    @Nested
    @DisplayName("Enum Constraint Tests - Requirement 11.8")
    class EnumConstraintTests {

        @Test
        @DisplayName("Should reject value not in enum list")
        void shouldRejectValueNotInEnumList() {
            FieldDefinition field = FieldDefinition.enumField("status", List.of("ACTIVE", "INACTIVE", "PENDING"));
            CollectionDefinition definition = createTestCollection(field);
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("status", "UNKNOWN"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("enum", result.errors().get(0).constraint());
            assertTrue(result.errors().get(0).message().contains("ACTIVE"));
        }

        @Test
        @DisplayName("Should accept value in enum list")
        void shouldAcceptValueInEnumList() {
            FieldDefinition field = FieldDefinition.enumField("status", List.of("ACTIVE", "INACTIVE", "PENDING"));
            CollectionDefinition definition = createTestCollection(field);
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("status", "ACTIVE"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should be case-sensitive for enum values")
        void shouldBeCaseSensitiveForEnumValues() {
            FieldDefinition field = FieldDefinition.enumField("status", List.of("ACTIVE", "INACTIVE"));
            CollectionDefinition definition = createTestCollection(field);
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("status", "active"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("enum", result.errors().get(0).constraint());
        }
    }

    @Nested
    @DisplayName("Reference Constraint Tests - Requirement 11.9")
    class ReferenceConstraintTests {

        @Test
        @DisplayName("Should reject reference to non-existent record")
        void shouldRejectReferenceToNonExistentRecord() {
            FieldDefinition field = FieldDefinition.reference("userId", "users");
            CollectionDefinition definition = createTestCollection(field);
            
            // Create target collection definition
            CollectionDefinition usersCollection = createTestCollection(
                FieldDefinition.requiredString("name")
            );
            
            // Mock registry to return the target collection
            when(collectionRegistry.get("users")).thenReturn(usersCollection);
            
            // Mock storage adapter to return empty (record not found)
            when(storageAdapter.getById(eq(usersCollection), eq("non-existent-id")))
                .thenReturn(Optional.empty());
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("userId", "non-existent-id"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("reference", result.errors().get(0).constraint());
            var fieldError = result.errors().get(0);
            assertTrue(fieldError.message().contains("non-existent-id"));
            assertTrue(fieldError.message().contains("users"));
            assertTrue(fieldError.message().contains("userId"));
            assertEquals(
                Map.of("field", "userId", "value", "non-existent-id", "targetCollection", "users"),
                fieldError.meta());
        }

        @Test
        @DisplayName("Should accept reference to existing record")
        void shouldAcceptReferenceToExistingRecord() {
            FieldDefinition field = FieldDefinition.reference("userId", "users");
            CollectionDefinition definition = createTestCollection(field);
            
            // Create target collection definition
            CollectionDefinition usersCollection = createTestCollection(
                FieldDefinition.requiredString("name")
            );
            
            // Mock registry to return the target collection
            when(collectionRegistry.get("users")).thenReturn(usersCollection);
            
            // Mock storage adapter to return the referenced record
            when(storageAdapter.getById(eq(usersCollection), eq("user-123")))
                .thenReturn(Optional.of(Map.of("id", "user-123", "name", "John")));
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("userId", "user-123"), OperationType.CREATE);
            
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should reject reference when target collection does not exist")
        void shouldRejectReferenceWhenTargetCollectionDoesNotExist() {
            FieldDefinition field = FieldDefinition.reference("userId", "non_existent_collection");
            CollectionDefinition definition = createTestCollection(field);
            
            // Mock registry to return null (collection not found)
            when(collectionRegistry.get("non_existent_collection")).thenReturn(null);
            
            ValidationResult result = validationEngine.validate(
                definition, Map.of("userId", "some-id"), OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals("reference", result.errors().get(0).constraint());
            assertTrue(result.errors().get(0).message().contains("does not exist"));
            var fieldError = result.errors().get(0);
            assertEquals(
                Map.of("field", "userId", "targetCollection", "non_existent_collection"),
                fieldError.meta());
            assertFalse(fieldError.meta().containsKey("value"));
        }
    }

    @Nested
    @DisplayName("Error Message Tests - Requirement 11.10")
    class ErrorMessageTests {

        @Test
        @DisplayName("Should return descriptive error message with field name")
        void shouldReturnDescriptiveErrorMessageWithFieldName() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.requiredString("username")
            );
            
            Map<String, Object> data = new HashMap<>();
            data.put("username", null);
            
            ValidationResult result = validationEngine.validate(definition, data, OperationType.CREATE);
            
            assertFalse(result.valid());
            FieldError error = result.errors().get(0);
            assertEquals("username", error.fieldName());
            assertEquals("nullable", error.constraint());
            assertNotNull(error.message());
            assertFalse(error.message().isEmpty());
        }

        @Test
        @DisplayName("Should return multiple errors for multiple violations")
        void shouldReturnMultipleErrorsForMultipleViolations() {
            FieldDefinition field1 = FieldDefinition.requiredString("name");
            FieldDefinition field2 = FieldDefinition.requiredInteger("age");
            CollectionDefinition definition = createTestCollection(field1, field2);
            
            Map<String, Object> data = new HashMap<>();
            data.put("name", null);
            data.put("age", null);
            
            ValidationResult result = validationEngine.validate(definition, data, OperationType.CREATE);
            
            assertFalse(result.valid());
            assertEquals(2, result.errorCount());
            assertTrue(result.hasErrorsForField("name"));
            assertTrue(result.hasErrorsForField("age"));
        }

        @Test
        @DisplayName("toErrorResponse should group errors by field")
        void toErrorResponseShouldGroupErrorsByField() {
            FieldDefinition field = new FieldDefinition(
                "password", FieldType.STRING, false, false, false, null,
                ValidationRules.forString(8, 20, "^(?=.*[A-Z])(?=.*[0-9]).*$"),
                null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);
            
            // Password too short and doesn't match pattern
            ValidationResult result = validationEngine.validate(
                definition, Map.of("password", "abc"), OperationType.CREATE);
            
            assertFalse(result.valid());
            
            Map<String, Object> errorResponse = result.toErrorResponse();
            assertFalse((Boolean) errorResponse.get("valid"));
            assertNotNull(errorResponse.get("errors"));
            
            @SuppressWarnings("unchecked")
            Map<String, List<String>> errors = (Map<String, List<String>>) errorResponse.get("errors");
            assertTrue(errors.containsKey("password"));
            assertTrue(errors.get("password").size() >= 1);
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests - Requirement 15.4")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should be stateless and produce consistent results")
        void shouldBeStatelessAndProduceConsistentResults() {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.requiredString("name"),
                FieldDefinition.requiredInteger("age")
            );
            
            Map<String, Object> validData = Map.of("name", "John", "age", 25);
            Map<String, Object> invalidData = new HashMap<>();
            invalidData.put("name", null);
            invalidData.put("age", 25);
            
            // Run multiple validations to ensure statelessness
            for (int i = 0; i < 100; i++) {
                ValidationResult validResult = validationEngine.validate(definition, validData, OperationType.CREATE);
                assertTrue(validResult.valid(), "Valid data should always pass validation");
                
                ValidationResult invalidResult = validationEngine.validate(definition, invalidData, OperationType.CREATE);
                assertFalse(invalidResult.valid(), "Invalid data should always fail validation");
            }
        }

        @Test
        @DisplayName("Should handle concurrent validation without interference")
        void shouldHandleConcurrentValidationWithoutInterference() throws InterruptedException {
            CollectionDefinition definition = createTestCollection(
                FieldDefinition.requiredString("name")
            );
            
            int threadCount = 10;
            int iterationsPerThread = 100;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);
            
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                new Thread(() -> {
                    try {
                        for (int i = 0; i < iterationsPerThread; i++) {
                            Map<String, Object> data = Map.of("name", "Thread" + threadId + "-" + i);
                            ValidationResult result = validationEngine.validate(definition, data, OperationType.CREATE);
                            if (result.valid()) {
                                successCount.incrementAndGet();
                            } else {
                                failureCount.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }
            
            latch.await();
            
            // All validations should succeed (valid data)
            assertEquals(threadCount * iterationsPerThread, successCount.get());
            assertEquals(0, failureCount.get());
        }
    }

    @Nested
    @DisplayName("Partial Update Tests")
    class PartialUpdateTests {

        @Test
        @DisplayName("Should skip validation for fields not provided in UPDATE")
        void shouldSkipValidationForFieldsNotProvidedInUpdate() {
            FieldDefinition requiredField = FieldDefinition.requiredString("name");
            FieldDefinition optionalField = FieldDefinition.string("description");
            CollectionDefinition definition = createTestCollection(requiredField, optionalField);
            
            // Only updating description, not providing name
            Map<String, Object> data = Map.of("description", "Updated description");
            
            ValidationResult result = validationEngine.validate(definition, data, OperationType.UPDATE);
            
            // Should pass because we're not updating the required field
            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Should validate provided fields in UPDATE")
        void shouldValidateProvidedFieldsInUpdate() {
            FieldDefinition field = new FieldDefinition(
                "age", FieldType.INTEGER, false, false, false, null,
                ValidationRules.forNumeric(0.0, 150.0), null, null, null
            );
            CollectionDefinition definition = createTestCollection(field);
            
            // Providing invalid age in update
            ValidationResult result = validationEngine.validate(
                definition, Map.of("age", 200), OperationType.UPDATE);
            
            assertFalse(result.valid());
            assertEquals("maxValue", result.errors().get(0).constraint());
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should throw NullPointerException when storageAdapter is null")
        void shouldThrowWhenStorageAdapterIsNull() {
            assertThrows(NullPointerException.class, () -> {
                new DefaultValidationEngine(null, collectionRegistry);
            });
        }

        @Test
        @DisplayName("Should allow null collectionRegistry")
        void shouldAllowNullCollectionRegistry() {
            DefaultValidationEngine engine = new DefaultValidationEngine(storageAdapter, null);
            assertNotNull(engine);
        }

        @Test
        @DisplayName("Should create engine with single-arg constructor")
        void shouldCreateEngineWithSingleArgConstructor() {
            DefaultValidationEngine engine = new DefaultValidationEngine(storageAdapter);
            assertNotNull(engine);
        }
    }

    @Nested
    @DisplayName("Null Parameter Tests")
    class NullParameterTests {

        @Test
        @DisplayName("Should throw NullPointerException when definition is null")
        void shouldThrowWhenDefinitionIsNull() {
            assertThrows(NullPointerException.class, () -> {
                validationEngine.validate(null, Map.of(), OperationType.CREATE);
            });
        }

        @Test
        @DisplayName("Should throw NullPointerException when data is null")
        void shouldThrowWhenDataIsNull() {
            CollectionDefinition definition = createTestCollection(FieldDefinition.string("name"));
            assertThrows(NullPointerException.class, () -> {
                validationEngine.validate(definition, null, OperationType.CREATE);
            });
        }

        @Test
        @DisplayName("Should throw NullPointerException when operationType is null")
        void shouldThrowWhenOperationTypeIsNull() {
            CollectionDefinition definition = createTestCollection(FieldDefinition.string("name"));
            assertThrows(NullPointerException.class, () -> {
                validationEngine.validate(definition, Map.of(), null);
            });
        }
    }
}
