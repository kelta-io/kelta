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
 * <p>Runs with order {@code 100} to execute after field validation and approval lock hooks.
 *
 * @since 1.0.0
 */
@Component
public class RecordTypeEnforcementHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(RecordTypeEnforcementHook.class);

    private static final String WILDCARD = "*";

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

            if (value == null || (value instanceof String s && s.isEmpty())) {
                // Apply default value if field is not provided
                if (restriction.defaultValue() != null && !restriction.defaultValue().isEmpty()) {
                    defaults.put(restriction.fieldName(), restriction.defaultValue());
                }
            } else {
                // Validate the value is in the allowed set
                String strValue = value.toString();
                if (!restriction.availableValues().contains(strValue)) {
                    errors.add(new BeforeSaveResult.ValidationError(
                            restriction.fieldName(),
                            String.format("Value '%s' is not allowed for record type. Allowed values: %s",
                                    strValue, restriction.availableValues())));
                }
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
            if (value != null && !(value instanceof String s && s.isEmpty())) {
                String strValue = value.toString();
                if (!restriction.availableValues().contains(strValue)) {
                    errors.add(new BeforeSaveResult.ValidationError(
                            restriction.fieldName(),
                            String.format("Value '%s' is not allowed for record type. Allowed values: %s",
                                    strValue, restriction.availableValues())));
                }
            }
        }

        if (!errors.isEmpty()) {
            return BeforeSaveResult.errors(errors);
        }

        return BeforeSaveResult.ok();
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
                SELECT rtp.available_values, rtp.default_value, f.name AS field_name
                FROM record_type_picklist rtp
                JOIN field f ON f.id = rtp.field_id
                WHERE rtp.record_type_id = ?
                """;

            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                String fieldName = rs.getString("field_name");
                String defaultValue = rs.getString("default_value");
                String availableValuesJson = rs.getString("available_values");

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

                return new PicklistRestriction(fieldName, availableValues, defaultValue);
            }, recordTypeId);
        } catch (Exception e) {
            log.error("Error loading picklist restrictions for record type '{}': {}",
                    recordTypeId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Represents a picklist restriction for a single field within a record type.
     */
    record PicklistRestriction(String fieldName, Set<String> availableValues, String defaultValue) {
    }
}
