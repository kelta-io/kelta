package io.kelta.runtime.validation;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.model.ValidationRules;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Default implementation of the ValidationEngine interface.
 * 
 * <p>This implementation is stateless and thread-safe, validating data against
 * collection definitions without maintaining any mutable state. All validation
 * operations are independent and can be executed concurrently.
 * 
 * <p>The engine validates the following constraints:
 * <ol>
 *   <li><b>Nullable</b> - reject null values for non-nullable fields</li>
 *   <li><b>Type</b> - value matches expected FieldType</li>
 *   <li><b>Min/Max value</b> - for numeric fields (INTEGER, LONG, DOUBLE)</li>
 *   <li><b>Min/Max length</b> - for STRING fields</li>
 *   <li><b>Pattern</b> - regex validation for STRING fields</li>
 *   <li><b>Immutable</b> - reject updates to immutable fields</li>
 *   <li><b>Unique</b> - check via StorageAdapter.isUnique()</li>
 *   <li><b>Enum</b> - value must be in enumValues list</li>
 *   <li><b>Reference</b> - referenced record must exist in target collection</li>
 * </ol>
 * 
 * @since 1.0.0
 */
public class DefaultValidationEngine implements ValidationEngine {
    
    private final StorageAdapter storageAdapter;
    private final CollectionRegistry collectionRegistry;
    
    /**
     * Creates a new DefaultValidationEngine.
     * 
     * @param storageAdapter the storage adapter for unique and reference checks
     * @param collectionRegistry the collection registry for reference validation (may be null if reference validation not needed)
     */
    public DefaultValidationEngine(StorageAdapter storageAdapter, CollectionRegistry collectionRegistry) {
        this.storageAdapter = Objects.requireNonNull(storageAdapter, "storageAdapter cannot be null");
        this.collectionRegistry = collectionRegistry;
    }
    
    /**
     * Creates a new DefaultValidationEngine without reference validation support.
     * 
     * @param storageAdapter the storage adapter for unique checks
     */
    public DefaultValidationEngine(StorageAdapter storageAdapter) {
        this(storageAdapter, null);
    }
    
    @Override
    public ValidationResult validate(CollectionDefinition definition, Map<String, Object> data, OperationType operationType) {
        return validate(definition, data, operationType, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden so {@code excludeId} actually reaches the uniqueness check.
     * The interface default silently ignores it — self-exclusion on update only
     * ever worked because callers validated a merged record whose map happened
     * to carry {@code id}. Update validation now runs against the bare patch
     * (which has no {@code id} key), so the explicit parameter is load-bearing.
     */
    @Override
    public ValidationResult validate(CollectionDefinition definition, Map<String, Object> data,
                                     OperationType operationType, String excludeId) {
        Objects.requireNonNull(definition, "definition cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");

        List<FieldError> errors = new ArrayList<>();

        for (FieldDefinition field : definition.fields()) {
            validateField(definition, field, data, operationType, excludeId, errors);
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Validates a single field against its definition.
     */
    private void validateField(
            CollectionDefinition definition,
            FieldDefinition field,
            Map<String, Object> data,
            OperationType operationType,
            String excludeId,
            List<FieldError> errors) {
        
        String fieldName = field.name();
        Object value = data.get(fieldName);
        boolean fieldProvided = data.containsKey(fieldName);
        
        // For UPDATE operations, skip validation of fields not provided in the data
        // (partial updates are allowed)
        if (operationType == OperationType.UPDATE && !fieldProvided) {
            return;
        }
        
        // 1. Nullable constraint check
        if (value == null) {
            if (!field.nullable()) {
                errors.add(FieldError.nullable(fieldName));
            }
            // Skip other validations if null (null is valid for nullable fields)
            return;
        }
        
        // 2. Immutable constraint check (only for updates)
        if (operationType == OperationType.UPDATE && field.immutable() && fieldProvided) {
            errors.add(FieldError.immutable(fieldName));
            // Continue with other validations even if immutable is violated
        }
        
        // 3. Type validation
        if (!isValidType(value, field.type())) {
            errors.add(FieldError.invalidType(fieldName, field.type().name()));
            // Skip further validations if type is wrong
            return;
        }
        
        // 4. Validation rules (min/max value, min/max length, pattern)
        ValidationRules rules = field.validationRules();
        if (rules != null) {
            validateRules(field, value, rules, errors);
        }
        
        // 5. Enum / Picklist validation
        if (field.enumValues() != null && !field.enumValues().isEmpty()) {
            if (field.type() == FieldType.MULTI_PICKLIST && value instanceof List<?> listValue) {
                validateMultiPicklist(field, listValue, errors);
            } else {
                validateEnum(field, value, errors);
            }
        }
        
        // 6. Unique constraint validation
        if (field.unique()) {
            validateUnique(definition, field, value, data, excludeId, errors);
        }
        
        // 7. Reference validation
        if (field.referenceConfig() != null) {
            validateReference(field, value, errors);
        }
    }
    
    /**
     * Validates that the value matches the expected field type.
     */
    private boolean isValidType(Object value, FieldType expectedType) {
        return switch (expectedType) {
            case STRING, TEXT, RICH_TEXT, ENCRYPTED, EXTERNAL_ID, PICKLIST -> value instanceof String;
            case VECTOR -> value instanceof List || value instanceof float[] || value instanceof double[];
            case EMAIL -> value instanceof String s && (s.isEmpty() || s.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"));
            case URL -> value instanceof String s && (s.isEmpty() || s.matches("^https?://.*|^ftp://.*"));
            case PHONE -> value instanceof String s && (s.isEmpty() || s.matches("^[+]?[0-9() \\-.ext]+$"));
            case INTEGER -> value instanceof Integer || isIntegerCompatible(value);
            case LONG -> value instanceof Long || value instanceof Integer || isLongCompatible(value);
            case DOUBLE, CURRENCY, PERCENT -> value instanceof Double || value instanceof Float ||
                           value instanceof Long || value instanceof Integer || isDoubleCompatible(value);
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> isValidDate(value);
            case DATETIME -> isValidDateTime(value);
            // JSON accepts any JSON value — objects, arrays, and scalars. Scalars
            // matter for fields.defaultValue, where a STRING field's default ("en")
            // arrives as a bare JSON string.
            case JSON -> value instanceof Map || value instanceof List
                    || value instanceof String || value instanceof Number || value instanceof Boolean;
            case GEOLOCATION -> value instanceof Map || value instanceof List;
            case REFERENCE, LOOKUP, MASTER_DETAIL -> value instanceof String;
            case ARRAY, MULTI_PICKLIST -> value instanceof List;
            case AUTO_NUMBER -> value instanceof String || value instanceof Number;
            case FORMULA, ROLLUP_SUMMARY -> true; // Computed fields accept any value (ignored)
        };
    }
    
    /**
     * Checks if a value can be interpreted as an Integer.
     */
    private boolean isIntegerCompatible(Object value) {
        if (value instanceof Number num) {
            double d = num.doubleValue();
            return d == Math.floor(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
        }
        return false;
    }
    
    /**
     * Checks if a value can be interpreted as a Long.
     */
    private boolean isLongCompatible(Object value) {
        if (value instanceof Number num) {
            double d = num.doubleValue();
            return d == Math.floor(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE;
        }
        return false;
    }
    
    /**
     * Checks if a value can be interpreted as a Double.
     */
    private boolean isDoubleCompatible(Object value) {
        return value instanceof Number;
    }
    
    /**
     * Validates a date value.
     *
     * <p>Accepts {@code LocalDate}, an ISO date string ({@code 2026-07-12}),
     * {@code java.util.Date}/{@code java.sql.Date} (JDBC read-back), and an ISO
     * datetime string at midnight ({@code 2026-07-12T00:00:00.000Z}) — a DATE
     * column reads back as a midnight datetime through the JSON layer, so this
     * canonical stored form must validate as a DATE. (The primary fix validates
     * only the patch on update; this keeps any other merged-validation path from
     * regressing.)
     */
    private boolean isValidDate(Object value) {
        if (value instanceof LocalDate || value instanceof java.util.Date) {
            return true;
        }
        if (value instanceof String str) {
            try {
                LocalDate.parse(str);
                return true;
            } catch (DateTimeParseException e) {
                // Fall through to the datetime-at-midnight form below.
            }
            try {
                java.time.Instant.parse(str); // e.g. 2026-07-12T00:00:00.000Z
                return true;
            } catch (DateTimeParseException e) {
                try {
                    LocalDateTime.parse(str); // e.g. 2026-07-12T00:00:00
                    return true;
                } catch (DateTimeParseException e2) {
                    return false;
                }
            }
        }
        return false;
    }
    
    /**
     * Validates a datetime value (ISO-8601 format string or LocalDateTime).
     */
    private boolean isValidDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return true;
        }
        if (value instanceof java.time.Instant) {
            return true;
        }
        if (value instanceof String str) {
            try {
                // Try parsing as LocalDateTime first
                LocalDateTime.parse(str);
                return true;
            } catch (DateTimeParseException e) {
                try {
                    // Try parsing as Instant (ISO-8601 with timezone)
                    java.time.Instant.parse(str);
                    return true;
                } catch (DateTimeParseException e2) {
                    return false;
                }
            }
        }
        return false;
    }
    
    /**
     * Validates min/max value and min/max length constraints.
     */
    private void validateRules(FieldDefinition field, Object value, ValidationRules rules, List<FieldError> errors) {
        String fieldName = field.name();
        
        // Min/Max value validation for numeric types
        if (value instanceof Number num) {
            double numValue = num.doubleValue();
            
            if (rules.minValue() != null && numValue < rules.minValue()) {
                errors.add(FieldError.minValue(fieldName, rules.minValue()));
            }
            if (rules.maxValue() != null && numValue > rules.maxValue()) {
                errors.add(FieldError.maxValue(fieldName, rules.maxValue()));
            }
        }
        
        // Length and pattern validation for strings
        if (value instanceof String str) {
            int length = str.length();
            
            if (rules.minLength() != null && length < rules.minLength()) {
                errors.add(FieldError.minLength(fieldName, rules.minLength()));
            }
            if (rules.maxLength() != null && length > rules.maxLength()) {
                errors.add(FieldError.maxLength(fieldName, rules.maxLength()));
            }
            
            // Pattern validation
            if (rules.pattern() != null) {
                try {
                    Pattern pattern = Pattern.compile(rules.pattern());
                    if (!pattern.matcher(str).matches()) {
                        errors.add(FieldError.pattern(fieldName));
                    }
                } catch (PatternSyntaxException e) {
                    // Invalid pattern in definition - log and skip
                    // In production, this should be caught during collection definition validation
                }
            }
        }
    }
    
    /**
     * Validates that the value is in the allowed enum values list.
     */
    private void validateEnum(FieldDefinition field, Object value, List<FieldError> errors) {
        String stringValue = value.toString();
        if (!field.enumValues().contains(stringValue)) {
            errors.add(FieldError.enumViolation(field.name(), field.enumValues()));
        }
    }

    /**
     * Validates that each value in a MULTI_PICKLIST list is in the allowed enum values.
     */
    private void validateMultiPicklist(FieldDefinition field, List<?> values, List<FieldError> errors) {
        for (Object item : values) {
            if (item != null) {
                String stringValue = item.toString();
                if (!field.enumValues().contains(stringValue)) {
                    errors.add(new FieldError(field.name(),
                            "Value '" + stringValue + "' is not a valid picklist option",
                            "picklist"));
                }
            }
        }
    }
    
    /**
     * Validates that the value is unique in the collection.
     */
    private void validateUnique(
            CollectionDefinition definition,
            FieldDefinition field,
            Object value,
            Map<String, Object> data,
            String excludeId,
            List<FieldError> errors) {

        // Record ID to exclude from the uniqueness check (for updates): the
        // explicit parameter wins; fall back to an id carried in the data map
        // (create-with-client-supplied-id, legacy 3-arg callers).
        String effectiveExcludeId = excludeId != null
                ? excludeId
                : (data.get("id") != null ? data.get("id").toString() : null);

        boolean isUnique = storageAdapter.isUnique(definition, field.name(), value, effectiveExcludeId);
        if (!isUnique) {
            errors.add(FieldError.unique(field.name()));
        }
    }
    
    /**
     * Validates that the referenced record exists in the target collection.
     */
    private void validateReference(FieldDefinition field, Object value, List<FieldError> errors) {
        ReferenceConfig refConfig = field.referenceConfig();
        
        if (collectionRegistry == null) {
            // Reference validation not available without registry
            return;
        }
        
        // Get the target collection definition
        CollectionDefinition targetCollection = collectionRegistry.get(refConfig.targetCollection());
        if (targetCollection == null) {
            // Target collection doesn't exist - this is a configuration error
            errors.add(FieldError.referenceTargetMissing(field.name(), refConfig.targetCollection()));
            return;
        }

        // Check if the referenced record exists
        String refId = value.toString();
        var referencedRecord = storageAdapter.getById(targetCollection, refId);

        if (referencedRecord.isEmpty()) {
            errors.add(FieldError.reference(field.name(), refConfig.targetCollection(), refId));
        }
    }
}
