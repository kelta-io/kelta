package io.kelta.worker.listener;

import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates a collection's ownership settings ({@code ownerField}, {@code ownerScope}) on save,
 * so a misconfigured owner-scoped collection is a 400 at configuration time rather than an
 * owner predicate against a column that cannot hold a user id.
 *
 * <ul>
 *   <li>{@code ownerScope} must be {@code NONE}, {@code PORTAL} or {@code ALL} (case-insensitive;
 *       normalized to upper case for the column's CHECK constraint).</li>
 *   <li>{@code ownerScope != NONE} requires an {@code ownerField} that is either the system
 *       {@code createdBy} or an active LOOKUP field of this collection whose target is
 *       {@code users}. A new collection has no fields yet, so only {@code createdBy} is valid at
 *       create time — set a LOOKUP owner after adding the field.</li>
 *   <li>System collections declare ownership in {@code SystemCollectionDefinitions}; their
 *       {@code collection} row is ignored, so setting it there is rejected.</li>
 * </ul>
 *
 * <p>Updates are validated only when they touch an ownership attribute, against the stored
 * settings overlaid with the patch.
 */
public class CollectionOwnershipValidationHook implements BeforeSaveHook {

    static final String INVALID_OWNER_SCOPE = "INVALID_OWNER_SCOPE";
    static final String INVALID_OWNER_FIELD = "INVALID_OWNER_FIELD";

    static final String CREATED_BY = "createdBy";

    private static final Set<String> OWNERSHIP_KEYS = Set.of("ownerField", "ownerScope");

    private static final String COUNT_USERS_LOOKUP = """
            SELECT COUNT(*) FROM field f
            LEFT JOIN collection rc ON rc.id = f.reference_collection_id
            WHERE f.collection_id = ? AND f.name = ? AND f.active = true
              AND (upper(f.type) = 'LOOKUP' OR upper(f.relationship_type) = 'LOOKUP')
              AND COALESCE(NULLIF(f.reference_target, ''), rc.name) = 'users'
            """;

    private final JdbcTemplate jdbcTemplate;

    public CollectionOwnershipValidationHook(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "collections";
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(null, record, record);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        if (OWNERSHIP_KEYS.stream().noneMatch(record::containsKey)) {
            return BeforeSaveResult.ok();
        }
        Map<String, Object> effective = new HashMap<>(previous != null ? previous : Map.of());
        effective.putAll(record);
        return validate(id, record, effective);
    }

    private BeforeSaveResult validate(String collectionId, Map<String, Object> patch,
                                      Map<String, Object> effective) {
        Object rawScope = effective.get("ownerScope");
        String scopeText = rawScope == null ? "" : rawScope.toString().trim();
        if (!scopeText.isEmpty() && !OwnerScope.parse(scopeText).name().equalsIgnoreCase(scopeText)) {
            return error("ownerScope", "Owner scope must be NONE, PORTAL or ALL", INVALID_OWNER_SCOPE);
        }
        OwnerScope scope = OwnerScope.parse(scopeText);
        if (scope == OwnerScope.NONE) {
            return normalized(patch, scopeText);
        }
        if (Boolean.TRUE.equals(effective.get("systemCollection"))) {
            return error("ownerScope",
                    "System collections declare ownership in code; it cannot be set here", INVALID_OWNER_SCOPE);
        }
        Object rawField = effective.get("ownerField");
        String ownerField = rawField == null ? "" : rawField.toString().trim();
        if (ownerField.isEmpty()) {
            return error("ownerField", "An owner field is required when records are owner-scoped",
                    INVALID_OWNER_FIELD);
        }
        if (!CREATED_BY.equals(ownerField) && !isUsersLookup(collectionId, ownerField)) {
            return error("ownerField", "Owner field '" + ownerField
                    + "' must be Created By or a lookup to Users on this collection", INVALID_OWNER_FIELD);
        }
        return normalized(patch, scopeText);
    }

    private boolean isUsersLookup(String collectionId, String fieldName) {
        if (collectionId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(COUNT_USERS_LOOKUP, Integer.class, collectionId, fieldName);
        return count != null && count > 0;
    }

    /** Stores the scope upper-cased (the column's CHECK is case-sensitive) when the patch set it. */
    private static BeforeSaveResult normalized(Map<String, Object> patch, String scopeText) {
        if (patch.containsKey("ownerScope") && !scopeText.isEmpty()
                && !scopeText.equals(scopeText.toUpperCase(Locale.ROOT))) {
            return BeforeSaveResult.withFieldUpdates(Map.of("ownerScope", scopeText.toUpperCase(Locale.ROOT)));
        }
        return BeforeSaveResult.ok();
    }

    private static BeforeSaveResult error(String field, String message, String code) {
        return BeforeSaveResult.errors(List.of(new BeforeSaveResult.ValidationError(field, message, code)));
    }
}
