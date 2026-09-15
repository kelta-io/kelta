package io.kelta.runtime.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FieldError} record.
 */
@DisplayName("FieldError Tests")
class FieldErrorTest {

    @Nested
    @DisplayName("Constructor Validation Tests")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should create field error with all parameters")
        void shouldCreateFieldErrorWithAllParameters() {
            FieldError error = new FieldError("name", "Field is required", "nullable");
            
            assertEquals("name", error.fieldName());
            assertEquals("Field is required", error.message());
            assertEquals("nullable", error.constraint());
        }

        @Test
        @DisplayName("Should throw NullPointerException when fieldName is null")
        void shouldThrowWhenFieldNameIsNull() {
            assertThrows(NullPointerException.class, () -> {
                new FieldError(null, "message", "constraint");
            });
        }

        @Test
        @DisplayName("Should throw NullPointerException when message is null")
        void shouldThrowWhenMessageIsNull() {
            assertThrows(NullPointerException.class, () -> {
                new FieldError("field", null, "constraint");
            });
        }

        @Test
        @DisplayName("Should throw NullPointerException when constraint is null")
        void shouldThrowWhenConstraintIsNull() {
            assertThrows(NullPointerException.class, () -> {
                new FieldError("field", "message", null);
            });
        }
    }

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("nullable() should create nullable constraint error")
        void nullableShouldCreateNullableConstraintError() {
            FieldError error = FieldError.nullable("name");
            
            assertEquals("name", error.fieldName());
            assertEquals("nullable", error.constraint());
            assertTrue(error.message().toLowerCase().contains("required"));
        }

        @Test
        @DisplayName("invalidType() should create type constraint error")
        void invalidTypeShouldCreateTypeConstraintError() {
            FieldError error = FieldError.invalidType("age", "INTEGER");
            
            assertEquals("age", error.fieldName());
            assertEquals("type", error.constraint());
            assertTrue(error.message().contains("INTEGER"));
        }

        @Test
        @DisplayName("minValue() should create minValue constraint error")
        void minValueShouldCreateMinValueConstraintError() {
            FieldError error = FieldError.minValue("age", 18);
            
            assertEquals("age", error.fieldName());
            assertEquals("minValue", error.constraint());
            assertTrue(error.message().contains("18"));
        }

        @Test
        @DisplayName("maxValue() should create maxValue constraint error")
        void maxValueShouldCreateMaxValueConstraintError() {
            FieldError error = FieldError.maxValue("score", 100);
            
            assertEquals("score", error.fieldName());
            assertEquals("maxValue", error.constraint());
            assertTrue(error.message().contains("100"));
        }

        @Test
        @DisplayName("minLength() should create minLength constraint error")
        void minLengthShouldCreateMinLengthConstraintError() {
            FieldError error = FieldError.minLength("username", 3);
            
            assertEquals("username", error.fieldName());
            assertEquals("minLength", error.constraint());
            assertTrue(error.message().contains("3"));
        }

        @Test
        @DisplayName("maxLength() should create maxLength constraint error")
        void maxLengthShouldCreateMaxLengthConstraintError() {
            FieldError error = FieldError.maxLength("code", 10);
            
            assertEquals("code", error.fieldName());
            assertEquals("maxLength", error.constraint());
            assertTrue(error.message().contains("10"));
        }

        @Test
        @DisplayName("pattern() should create pattern constraint error")
        void patternShouldCreatePatternConstraintError() {
            FieldError error = FieldError.pattern("email");
            
            assertEquals("email", error.fieldName());
            assertEquals("pattern", error.constraint());
            assertTrue(error.message().toLowerCase().contains("pattern"));
        }

        @Test
        @DisplayName("immutable() should create immutable constraint error")
        void immutableShouldCreateImmutableConstraintError() {
            FieldError error = FieldError.immutable("createdAt");
            
            assertEquals("createdAt", error.fieldName());
            assertEquals("immutable", error.constraint());
            assertTrue(error.message().toLowerCase().contains("immutable"));
        }

        @Test
        @DisplayName("unique() should create unique constraint error")
        void uniqueShouldCreateUniqueConstraintError() {
            FieldError error = FieldError.unique("email");
            
            assertEquals("email", error.fieldName());
            assertEquals("unique", error.constraint());
            assertTrue(error.message().toLowerCase().contains("unique"));
        }

        @Test
        @DisplayName("enumViolation() should create enum constraint error with allowed values")
        void enumViolationShouldCreateEnumConstraintErrorWithAllowedValues() {
            FieldError error = FieldError.enumViolation("status", List.of("ACTIVE", "INACTIVE"));
            
            assertEquals("status", error.fieldName());
            assertEquals("enum", error.constraint());
            assertTrue(error.message().contains("ACTIVE"));
            assertTrue(error.message().contains("INACTIVE"));
        }

        @Test
        @DisplayName("reference() should create reference constraint error with meta")
        void referenceShouldCreateReferenceConstraintError() {
            FieldError error = FieldError.reference("userId", "users", "not-a-real-id");

            assertEquals("userId", error.fieldName());
            assertEquals("reference", error.constraint());
            assertTrue(error.message().contains("users"));
            assertTrue(error.message().contains("not-a-real-id"));
            assertEquals(
                java.util.Map.of("field", "userId", "value", "not-a-real-id", "targetCollection", "users"),
                error.meta());
        }

        @Test
        @DisplayName("referenceTargetMissing() should create reference error with no value in meta")
        void referenceTargetMissingShouldCreateReferenceErrorWithNoValueInMeta() {
            FieldError error = FieldError.referenceTargetMissing("userId", "non_existent_collection");

            assertEquals("userId", error.fieldName());
            assertEquals("reference", error.constraint());
            assertTrue(error.message().contains("non_existent_collection"));
            assertEquals(
                java.util.Map.of("field", "userId", "targetCollection", "non_existent_collection"),
                error.meta());
            assertFalse(error.meta().containsKey("value"));
        }
    }

    @Nested
    @DisplayName("Record Equality Tests")
    class EqualityTests {

        @Test
        @DisplayName("Should be equal when all fields match")
        void shouldBeEqualWhenFieldsMatch() {
            FieldError error1 = new FieldError("name", "Field is required", "nullable");
            FieldError error2 = new FieldError("name", "Field is required", "nullable");
            
            assertEquals(error1, error2);
            assertEquals(error1.hashCode(), error2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when fields differ")
        void shouldNotBeEqualWhenFieldsDiffer() {
            FieldError error1 = new FieldError("name", "Field is required", "nullable");
            FieldError error2 = new FieldError("age", "Field is required", "nullable");
            
            assertNotEquals(error1, error2);
        }
    }
}
