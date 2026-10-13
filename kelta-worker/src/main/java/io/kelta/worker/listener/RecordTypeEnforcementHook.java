package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wildcard before-save hook that enforces record type constraints on user-defined collections.
 *
 * <p>When a record includes a {@code recordTypeId}, this hook validates that any picklist
 * field values are within the allowed values configured for that record type. On creation,
 * it also applies type-specific default values for picklist fields that are not provided.
 *
 * <p>A {@code multi_picklist} field is checked per element: every selected value must be
 * allowed, and the error names the ones that are not. Its value is normally a {@code List}
 * (the JSON:API array the validation engine requires); an array, a JSON array string or a
 * comma-separated string (the CSV-import encodings) is accepted too. Its record-type default
 * is applied as a single-element list, the field's native shape.
 *
 * <p>Runs with order {@code 100} to execute after field validation and approval lock hooks.
 *
 * @since 1.0.0
 */
@Component
public class RecordTypeEnforcementHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(RecordTypeEnforcementHook.class);

    private static final String WILDCARD = "*";

    /** {@code field.type} of a multi-select picklist (compared case-insensitively). */
    private static final String MULTI_PICKLIST_TYPE = "multi_picklist";

    /**
     * System collections that should never have record type enforcement.
     */
    private static final Set<String> EXCLUDED_COLLECTIONS = Set.of(
            "collections", "fields", "profiles", "users", "groups",
            "record-types", "record-type-picklists", "validation-rules",
            "page-layouts", "layout-sections", "layout-fields",
            "layout-related-lists", "layout-assignments",
            "approval-processes", "approval-steps", "approval-instances",
            "approval-step-instances", "flows", "flow-versions",
            "workflow-rules", "email-templates", "scheduled-jobs",
            "connected-apps", "oidc-providers",
            "profile-system-permissions", "profile-object-permissions",
            "profile-field-permissions", "global-picklists", "picklist-values",
            "picklist-dependencies", "scripts", "script-triggers",
            "ui-pages", "ui-components", "ui-menus", "ui-menu-items",
            "dashboards", "dashboard-components", "list-views",
            "connected-app-tokens", "reports", "report-columns"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RecordTypeEnforcementHook(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCollectionName() {
        return WILDCARD;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        String collectionName = (String) record.get("__collectionName");
        if (collectionName != null && EXCLUDED_COLLECTIONS.contains(collectionName)) {
            return BeforeSaveResult.ok();
        }

        String recordTypeId = extractRecordTypeId(record);
        if (recordTypeId == null) {
            return BeforeSaveResult.ok();
        }

        // Validate the record type exists and is active
        if (!isValidRecordType(recordTypeId, tenantId)) {
            return BeforeSaveResult.error("recordTypeId",
                    "Invalid or inactive record type.");
        }

        // Load picklist restrictions for this record type
        List<PicklistRestriction> restrictions = loadPicklistRestrictions(recordTypeId);
        if (restrictions.isEmpty()) {
            return BeforeSaveResult.ok();
        }

        // Validate picklist values and collect defaults
        List<BeforeSaveResult.ValidationError> errors = new ArrayList<>();
        Map<String, Object> defaults = new HashMap<>();

        for (PicklistRestriction restriction : restrictions) {
            Object value = record.get(restriction.fieldName());

            if (isEmptyValue(value)) {
                // Apply default value if field is not provided
                if (restriction.defaultValue() != null && !restriction.defaultValue().isEmpty()) {
                    defaults.put(restriction.fieldName(), defaultFor(restriction));
                }
            } else {
                validateValue(restriction, value, errors);
            }
        }

        if (!errors.isEmpty()) {
            return BeforeSaveResult.errors(errors);
        }

        if (!defaults.isEmpty()) {
            return BeforeSaveResult.withFieldUpdates(defaults);
        }

        return BeforeSaveResult.ok();
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        String collectionName = (String) record.get("__collectionName");
        if (collectionName != null && EXCLUDED_COLLECTIONS.contains(collectionName)) {
            return BeforeSaveResult.ok();
        }

        // Use recordTypeId from the update data, or fall back to previous
        String recordTypeId = extractRecordTypeId(record);
        if (recordTypeId == null && previous != null) {
            recordTypeId = extractRecordTypeId(previous);
        }
        if (recordTypeId == null) {
            return BeforeSaveResult.ok();
        }

        // If changing the record type, validate the new one is active
        String newRecordTypeId = extractRecordTypeId(record);
        if (newRecordTypeId != null && !isValidRecordType(newRecordTypeId, tenantId)) {
            return BeforeSaveResult.error("recordTypeId",
                    "Invalid or inactive record type.");
        }

        // Load picklist restrictions for the effective record type
        List<PicklistRestriction> restrictions = loadPicklistRestrictions(recordTypeId);
        if (restrictions.isEmpty()) {
            return BeforeSaveResult.ok();
        }

        // Only validate fields that are being updated
        List<BeforeSaveResult.ValidationError> errors = new ArrayList<>();
        for (PicklistRestriction restriction : restrictions) {
            if (!record.containsKey(restriction.fieldName())) {
                continue; // Field not being updated, skip
            }

            Object value = record.get(restriction.fieldName());
            if (!isEmptyValue(value)) {
                validateValue(restriction, value, errors);
            }
        }

        if (!errors.isEmpty()) {
            return BeforeSaveResult.errors(errors);
        }

        return BeforeSaveResult.ok();
    }

    /**
     * Adds a validation error when {@code value} holds anything the record type does not allow.
     * A multi-valued field (declared {@code multi_picklist}, or any list/array value) is checked
     * per element; a single picklist compares its string form, as it always has.
     */
    private void validateValue(PicklistRestriction restriction, Object value,
                               List<BeforeSaveResult.ValidationError> errors) {
        if (restriction.multiValued() || value instanceof Collection<?> || value.getClass().isArray()) {
            Set<String> disallowed = new LinkedHashSet<>();
            for (String element : toValueList(value)) {
                if (!restriction.availableValues().contains(element)) {
                    disallowed.add(element);
                }
            }
            if (disallowed.isEmpty()) {
                return;
            }
            String message = disallowed.size() == 1
                    ? String.format("Value '%s' is not allowed for record type. Allowed values: %s",
                            disallowed.iterator().next(), restriction.availableValues())
                    : String.format("Values %s are not allowed for record type. Allowed values: %s",
                            quoteAll(disallowed), restriction.availableValues());
            errors.add(new BeforeSaveResult.ValidationError(restriction.fieldName(), message));
            return;
        }

        String strValue = value.toString();
        if (!restriction.availableValues().contains(strValue)) {
            errors.add(new BeforeSaveResult.ValidationError(
                    restriction.fieldName(),
                    String.format("Value '%s' is not allowed for record type. Allowed values: %s",
                            strValue, restriction.availableValues())));
        }
    }

    /**
     * Whether a field value counts as "not provided": {@code null}, an empty string, or an empty
     * list/array (a multi-picklist with nothing selected).
     */
    private static boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        return value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0;
    }

    /**
     * The record type's default in the field's native shape: the string for a picklist, a list
     * for a multi-picklist (a single value, or the values of a JSON array default).
     */
    private Object defaultFor(PicklistRestriction restriction) {
        if (!restriction.multiValued()) {
            return restriction.defaultValue();
        }
        String trimmed = restriction.defaultValue().trim();
        if (trimmed.startsWith("[")) {
            List<String> parsed = parseJsonArray(trimmed);
            if (parsed != null) {
                return parsed;
            }
        }
        return List.of(restriction.defaultValue());
    }

    /**
     * The selected values of a multi-picklist value, in order, blanks dropped. Accepts a
     * {@code Collection}, an array, a JSON array string or a comma-separated string (the
     * encodings {@code CsvImportService} reads); any other value is a single selection.
     */
    private List<String> toValueList(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            for (Object element : c) {
                addElement(values, element);
            }
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addElement(values, java.lang.reflect.Array.get(value, i));
            }
        } else if (value instanceof String s) {
            String trimmed = s.trim();
            List<String> parsed = trimmed.startsWith("[") ? parseJsonArray(trimmed) : null;
            if (parsed != null) {
                values.addAll(parsed);
            } else if (trimmed.startsWith("[")) {
                addElement(values, trimmed);
            } else {
                for (String part : trimmed.split(",")) {
                    addElement(values, part.trim());
                }
            }
        } else {
            addElement(values, value);
        }
        return values;
    }

    private static void addElement(List<String> values, Object element) {
        if (element != null && !element.toString().isEmpty()) {
            values.add(element.toString());
        }
    }

    /** A JSON array of scalars as strings (blanks dropped), or {@code null} if it is not one. */
    private List<String> parseJsonArray(String json) {
        try {
            List<?> parsed = objectMapper.readValue(json, List.class);
            List<String> values = new ArrayList<>();
            for (Object element : parsed) {
                if (element instanceof Map<?, ?> || element instanceof Collection<?>) {
                    return null;
                }
                addElement(values, element);
            }
            return values;
        } catch (Exception e) {
            return null;
        }
    }

    private static String quoteAll(Set<String> values) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String v : values) {
            joiner.add("'" + v + "'");
        }
        return joiner.toString();
    }

    private String extractRecordTypeId(Map<String, Object> record) {
        Object val = record.get("recordTypeId");
        if (val == null) {
            // Also check snake_case form (from DB reads)
            val = record.get("record_type_id");
        }
        return val != null ? val.toString() : null;
    }

    private boolean isValidRecordType(String recordTypeId, String tenantId) {
        try {
            String sql = "SELECT COUNT(*) FROM record_type WHERE id = ? AND is_active = true";
            List<Object> params = new ArrayList<>();
            params.add(recordTypeId);
            if (tenantId != null) {
                sql += " AND tenant_id = ?";
                params.add(tenantId);
            }
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("Error validating record type '{}': {}", recordTypeId, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<PicklistRestriction> loadPicklistRestrictions(String recordTypeId) {
        try {
            String sql = """
                SELECT rtp.available_values, rtp.default_value, f.name AS field_name,
                       f.type AS field_type
                FROM record_type_picklist rtp
                JOIN field f ON f.id = rtp.field_id
                WHERE rtp.record_type_id = ?
                """;

            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                String fieldName = rs.getString("field_name");
                String defaultValue = rs.getString("default_value");
                String availableValuesJson = rs.getString("available_values");
                boolean multiValued = MULTI_PICKLIST_TYPE.equalsIgnoreCase(rs.getString("field_type"));

                Set<String> availableValues = new LinkedHashSet<>();
                try {
                    List<String> valuesList = objectMapper.readValue(
                            availableValuesJson,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    availableValues.addAll(valuesList);
                } catch (Exception e) {
                    log.warn("Failed to parse available_values for field '{}': {}",
                            fieldName, e.getMessage());
                }

                return new PicklistRestriction(fieldName, availableValues, defaultValue, multiValued);
            }, recordTypeId);
        } catch (Exception e) {
            log.error("Error loading picklist restrictions for record type '{}': {}",
                    recordTypeId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Represents a picklist restriction for a single field within a record type.
     *
     * @param multiValued whether the field is a {@code multi_picklist} (validated per element,
     *                    defaulted as a list)
     */
    record PicklistRestriction(String fieldName, Set<String> availableValues, String defaultValue,
                               boolean multiValued) {

        /** A single-value picklist restriction. */
        PicklistRestriction(String fieldName, Set<String> availableValues, String defaultValue) {
            this(fieldName, availableValues, defaultValue, false);
        }
    }
}
